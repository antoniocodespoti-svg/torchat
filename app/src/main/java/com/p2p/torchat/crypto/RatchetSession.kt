package com.p2p.torchat.crypto

/**
 * Simplified Double Ratchet Session.
 * Manages sending and receiving chains with sequence numbers.
 * Includes Replay Protection (REPLAY-001).
 */
class RatchetSession(initialRootKey: ByteArray) {
    private var sendChainKey: ByteArray = initialRootKey
    private var receiveChainKey: ByteArray = initialRootKey

    var sendSequence: Int = 0
        private set
    var receiveSequence: Int = 0
        private set

    // Track processed sequence numbers to prevent replay attacks
    private val processedSequences = mutableSetOf<Int>()
    private val maxSequenceWindow = 1000

    /**
     * Advances the sending chain and returns a new Message Key.
     */
    fun nextSendKey(): ByteArray {
        val result = E2EManager.kdfRatchet(sendChainKey, "send-chain-step")
        sendChainKey = result.first
        sendSequence++
        return result.second
    }

    /**
     * Advances the receiving chain and returns a new Message Key.
     * Verifies sequence number for replay protection.
     */
    fun nextReceiveKey(seqNum: Int): ByteArray {
        // Replay Protection
        if (processedSequences.contains(seqNum) || seqNum < receiveSequence - maxSequenceWindow) {
            throw SecurityException("Replay attack detected or message too old (seq: $seqNum)")
        }

        val result = E2EManager.kdfRatchet(receiveChainKey, "receive-chain-step")
        receiveChainKey = result.first

        // Update watermark
        if (seqNum >= receiveSequence) {
            receiveSequence = seqNum + 1
        }
        processedSequences.add(seqNum)

        // Trim window
        if (processedSequences.size > maxSequenceWindow) {
            processedSequences.removeIf { it < (receiveSequence - maxSequenceWindow) }
        }

        return result.second
    }

    fun getSessionContext(seqNum: Int): String {
        return "v2|seq:$seqNum"
    }
}
