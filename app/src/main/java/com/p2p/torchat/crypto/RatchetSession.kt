package com.p2p.torchat.crypto

import com.p2p.torchat.util.Constants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Symmetric Ratchet Session.
 * Implements KDF chain with skipped message keys support for out-of-order delivery.
 * Resolves REPLAY-001 and CRYPTO-003.
 */
class SymmetricRatchetSession(
    val sessionId: String,
    initialSendKey: ByteArray,
    initialReceiveKey: ByteArray
) {
    private val mutex = Mutex()
    private var sendChainKey: ByteArray = initialSendKey
    private var receiveChainKey: ByteArray = initialReceiveKey

    var sendSequence: Int = 0
        private set
    var receiveSequence: Int = 0
        private set

    data class SendKey(val key: ByteArray, val sequence: Int)

    // Keys skipped due to out-of-order delivery
    private val skippedMessageKeys = mutableMapOf<Int, ByteArray>()
    private val maxSkipDuration = 100 // Maximum number of messages that can be skipped

    /**
     * Advances the sending chain and returns a new Message Key and its sequence number.
     * Resolves RATCHET-003 (atomic key/sequence pair).
     */
    suspend fun nextSendKey(): SendKey = mutex.withLock {
        val result = E2EManager.kdfRatchet(sendChainKey, "chain-step")
        sendChainKey = result.first // Next Chain Key
        val messageKey = result.second
        sendSequence++
        return@withLock SendKey(messageKey, sendSequence)
    }

    /**
     * Attempts to decrypt a message. Advances the ratchet only if decryption is successful.
     * Resolves Audit Point 3 and RATCHET-004 (thread-safety).
     */
    suspend fun tryDecrypt(
        seqNum: Int,
        encB64: String,
        aad: ByteArray,
        decryptFn: (String, ByteArray, ByteArray) -> String
    ): String = mutex.withLock {
        // 1. Check if the key was already skipped and stored
        skippedMessageKeys[seqNum]?.let { key ->
            val decrypted = decryptFn(encB64, key, aad)
            skippedMessageKeys.remove(seqNum)
            return@withLock decrypted
        }

        // 2. Prevent replay or extremely old messages
        // If seqNum is 1, and receiveSequence is 0, it's valid.
        if (seqNum <= receiveSequence) {
            throw SecurityException("Replay attack detected or message too old (seq: $seqNum, current: $receiveSequence)")
        }

        // 3. Prevent DoS via large gaps
        if (seqNum - receiveSequence > maxSkipDuration) {
            throw SecurityException("Too many skipped messages: gap is ${seqNum - receiveSequence}")
        }

        // 4. Temporary state for potential rollback
        var currentReceiveChainKey = receiveChainKey
        var currentReceiveSequence = receiveSequence
        val newSkipped = mutableMapOf<Int, ByteArray>()

        // 5. Advance the chain until we reach the target sequence
        // Example: seqNum=1, currentReceiveSequence=0. Loop doesn't run.
        // Example: seqNum=5, currentReceiveSequence=0. Loop runs for seq 1, 2, 3, 4.
        while (currentReceiveSequence < seqNum - 1) {
            val result = E2EManager.kdfRatchet(currentReceiveChainKey, "chain-step")
            currentReceiveChainKey = result.first
            val messageKey = result.second
            currentReceiveSequence++
            newSkipped[currentReceiveSequence] = messageKey
        }

        // 6. Generate the key for the current sequence
        val result = E2EManager.kdfRatchet(currentReceiveChainKey, "chain-step")
        val targetKey = result.second
        val nextChainKey = result.first

        // 7. Try to decrypt before committing state
        val decrypted = decryptFn(encB64, targetKey, aad)

        // 8. Commitment: Update state only after successful decryption
        receiveChainKey = nextChainKey
        receiveSequence = seqNum
        skippedMessageKeys.putAll(newSkipped)

        return@withLock decrypted
    }

    /**
     * Returns a context string for AAD.
     */
    fun getSessionContext(seqNum: Int): String {
        return "v1|seq:$seqNum"
    }
}
