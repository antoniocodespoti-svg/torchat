package com.p2p.torchat.crypto

import com.p2p.torchat.util.Constants

/**
 * Symmetric Ratchet Session.
 * Implements KDF chain with skipped message keys support for out-of-order delivery.
 * Resolves REPLAY-001 and CRYPTO-003.
 */
class SymmetricRatchetSession(initialSendKey: ByteArray, initialReceiveKey: ByteArray) {
    private var sendChainKey: ByteArray = initialSendKey
    private var receiveChainKey: ByteArray = initialReceiveKey

    var sendSequence: Int = 0
        private set
    var receiveSequence: Int = 0
        private set

    // Keys skipped due to out-of-order delivery
    private val skippedMessageKeys = mutableMapOf<Int, ByteArray>()
    private val maxSkipDuration = 100 // Maximum number of messages that can be skipped

    /**
     * Advances the sending chain and returns a new Message Key.
     */
    fun nextSendKey(): ByteArray {
        val result = E2EManager.kdfRatchet(sendChainKey, "send")
        sendChainKey = result.first // Next Chain Key
        val messageKey = result.second
        sendSequence++
        return messageKey
    }

    /**
     * Attempts to decrypt a message. Advances the ratchet only if decryption is successful.
     * Resolves Audit Point 3.
     */
    fun tryDecrypt(
        seqNum: Int,
        encB64: String,
        aad: ByteArray,
        decryptFn: (String, ByteArray, ByteArray) -> String
    ): String {
        // 1. Check if the key was already skipped and stored
        skippedMessageKeys[seqNum]?.let { key ->
            val decrypted = decryptFn(encB64, key, aad)
            skippedMessageKeys.remove(seqNum)
            return decrypted
        }

        // 2. Prevent replay or extremely old messages
        if (seqNum < receiveSequence) {
            throw SecurityException("Replay attack detected or message too old (seq: $seqNum)")
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
        while (currentReceiveSequence < seqNum) {
            val result = E2EManager.kdfRatchet(currentReceiveChainKey, "receive")
            currentReceiveChainKey = result.first
            newSkipped[currentReceiveSequence] = result.second
            currentReceiveSequence++
        }

        // 6. Generate the key for the current sequence
        val result = E2EManager.kdfRatchet(currentReceiveChainKey, "receive")
        val targetKey = result.second
        val nextChainKey = result.first

        // 7. Try to decrypt before committing state
        val decrypted = decryptFn(encB64, targetKey, aad)

        // 8. Commitment: Update state only after successful decryption
        receiveChainKey = nextChainKey
        receiveSequence = currentReceiveSequence + 1
        skippedMessageKeys.putAll(newSkipped)

        return decrypted
    }

    /**
     * Returns a context string for AAD.
     */
    fun getSessionContext(seqNum: Int): String {
        return "v1|seq:$seqNum"
    }
}
