package com.p2p.torchat.crypto

/**
 * Simplified Double Ratchet Session.
 * Manages sending and receiving chains with sequence numbers.
 * Resolves Audit Points 2 and 3.
 */
class RatchetSession(initialRootKey: ByteArray) {
    private var sendChainKey: ByteArray = initialRootKey
    private var receiveChainKey: ByteArray = initialRootKey

    var sendSequence: Int = 0
        private set
    var receiveSequence: Int = 0
        private set

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
     */
    fun nextReceiveKey(): ByteArray {
        val result = E2EManager.kdfRatchet(receiveChainKey, "receive-chain-step")
        receiveChainKey = result.first
        receiveSequence++
        return result.second
    }

    fun getSessionContext(seqNum: Int): String {
        return "v2|seq:$seqNum"
    }
}
