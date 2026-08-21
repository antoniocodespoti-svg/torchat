package com.p2p.torchat.crypto

import com.p2p.torchat.util.Constants

/**
 * Symmetric Ratchet Session.
 * Implements KDF chain with skipped message keys support for out-of-order delivery.
 * Resolves REPLAY-001 and CRYPTO-003.
 */
class SymmetricRatchetSession(initialRootKey: ByteArray) {
    private var sendChainKey: ByteArray = initialRootKey
    private var receiveChainKey: ByteArray = initialRootKey

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
        val result = E2EManager.kdfRatchet(sendChainKey, "send-chain-step")
        sendChainKey = result.first // Next Chain Key
        val messageKey = result.second
        sendSequence++
        return messageKey
    }

    /**
     * Advances the receiving chain to the specified sequence number.
     * Returns the Message Key for that sequence, handling skips if necessary.
     */
    fun getReceiveKey(seqNum: Int): ByteArray {
        // 1. Check if the key was already skipped and stored
        skippedMessageKeys[seqNum]?.let {
            val key = it
            skippedMessageKeys.remove(seqNum)
            return key
        }

        // 2. Prevent replay or extremely old messages
        if (seqNum < receiveSequence) {
            throw SecurityException("Replay attack detected or message too old (seq: $seqNum)")
        }

        // 3. Prevent DoS via large gaps
        if (seqNum - receiveSequence > maxSkipDuration) {
            throw SecurityException("Too many skipped messages: gap is ${seqNum - receiveSequence}")
        }

        // 4. Advance the chain until we reach the target sequence
        while (receiveSequence < seqNum) {
            val result = E2EManager.kdfRatchet(receiveChainKey, "receive-chain-step")
            receiveChainKey = result.first
            skippedMessageKeys[receiveSequence] = result.second
            receiveSequence++
        }

        // 5. Generate the key for the current sequence
        val result = E2EManager.kdfRatchet(receiveChainKey, "receive-chain-step")
        receiveChainKey = result.first
        receiveSequence++

        return result.second
    }

    /**
     * Returns a context string for AAD.
     */
    fun getSessionContext(seqNum: Int): String {
        return "v1|seq:$seqNum"
    }
}
