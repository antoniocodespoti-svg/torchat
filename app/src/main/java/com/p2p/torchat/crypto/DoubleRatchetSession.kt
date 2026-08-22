package com.p2p.torchat.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyPair
import java.security.PublicKey

/**
 * Full Double Ratchet Session implementation.
 * Resolves Audit Point 2.
 */
class DoubleRatchetSession(
    val sessionId: String,
    initialRootKey: ByteArray,
    initialMyRatchetKeyPair: KeyPair,
    initialPeerRatchetPublicKey: PublicKey? = null
) {
    private val mutex = Mutex()

    private var rootKey = initialRootKey
    private var sendChainKey: ByteArray? = null
    private var receiveChainKey: ByteArray? = null

    private var myRatchetKeyPair = initialMyRatchetKeyPair
    private var peerRatchetPublicKey = initialPeerRatchetPublicKey

    private var sendSequence = 0
    private var receiveSequence = 0
    private var previousReceiveSequence = 0

    private val skippedMessageKeys = mutableMapOf<Pair<String, Int>, ByteArray>()
    private val MAX_SKIPPED_KEYS = 1000

    data class SendResult(
        val messageKey: ByteArray,
        val sequence: Int,
        val ratchetPublicKey: PublicKey
    )

    init {
        // If we have peer's key initially (Initiator), we can set up the first sending chain
        if (peerRatchetPublicKey != null) {
            val dhOut = E2EManager.calculateSharedSecret(myRatchetKeyPair.private, peerRatchetPublicKey!!)
            val (newRoot, newSendCK) = E2EManager.kdfRoot(rootKey, dhOut)
            rootKey = newRoot
            sendChainKey = newSendCK
        }
    }

    suspend fun nextSendKey(): SendResult = mutex.withLock {
        if (sendChainKey == null) {
            // Initial DH for Responder or re-initialization
            if (peerRatchetPublicKey != null) {
                myRatchetKeyPair = E2EManager.generateEphemeralKeyPair()
                val dhOut = E2EManager.calculateSharedSecret(myRatchetKeyPair.private, peerRatchetPublicKey!!)
                val (newRoot, newSendCK) = E2EManager.kdfRoot(rootKey, dhOut)
                rootKey = newRoot
                sendChainKey = newSendCK
            } else {
                throw IllegalStateException("Send chain not initialized")
            }
        }

        val (nextCK, mk) = E2EManager.kdfChain(sendChainKey!!, "constant")
        sendChainKey = nextCK
        val result = SendResult(mk, sendSequence, myRatchetKeyPair.public)
        sendSequence++
        return@withLock result
    }

    suspend fun tryDecrypt(
        newPeerPubKey: PublicKey,
        seqNum: Int,
        encB64: String,
        aad: ByteArray,
        decryptFn: (String, ByteArray, ByteArray) -> String
    ): String = mutex.withLock {
        val pubKeyStr = E2EManager.publicKeyToString(newPeerPubKey)

        // 1. Check skipped keys
        skippedMessageKeys[pubKeyStr to seqNum]?.let { key ->
            val decrypted = decryptFn(encB64, key, aad)
            skippedMessageKeys.remove(pubKeyStr to seqNum)
            return@withLock decrypted
        }

        // 2. DH Ratchet Step if needed
        if (newPeerPubKey != peerRatchetPublicKey) {
            skipMessageKeys(targetSeq = seqNum)
            dhRatchetStep(newPeerPubKey)
        }

        // 3. Normal symmetric advancement
        skipMessageKeys(targetSeq = seqNum)
        val (nextCK, mk) = E2EManager.kdfChain(receiveChainKey!!, "constant")

        val decrypted = decryptFn(encB64, mk, aad)

        // 4. Commit
        receiveChainKey = nextCK
        receiveSequence++

        return@withLock decrypted
    }

    private fun dhRatchetStep(newPeerPubKey: PublicKey) {
        previousReceiveSequence = receiveSequence
        receiveSequence = 0
        sendSequence = 0
        peerRatchetPublicKey = newPeerPubKey

        // Receiving Chain
        val dhOutRecv = E2EManager.calculateSharedSecret(myRatchetKeyPair.private, peerRatchetPublicKey!!)
        val (rootAfterRecv, newRecvCK) = E2EManager.kdfRoot(rootKey, dhOutRecv)
        rootKey = rootAfterRecv
        receiveChainKey = newRecvCK

        // Sending Chain
        myRatchetKeyPair = E2EManager.generateEphemeralKeyPair()
        val dhOutSend = E2EManager.calculateSharedSecret(myRatchetKeyPair.private, peerRatchetPublicKey!!)
        val (rootAfterSend, newSendCK) = E2EManager.kdfRoot(rootKey, dhOutSend)
        rootKey = rootAfterSend
        sendChainKey = newSendCK
    }

    private fun skipMessageKeys(targetSeq: Int) {
        if (receiveChainKey == null) return

        if (receiveSequence + (targetSeq - receiveSequence) > MAX_SKIPPED_KEYS) {
            throw SecurityException("Too many skipped messages")
        }

        val currentPeerKeyStr = E2EManager.publicKeyToString(peerRatchetPublicKey!!)
        while (receiveSequence < targetSeq) {
            val (nextCK, mk) = E2EManager.kdfChain(receiveChainKey!!, "constant")
            receiveChainKey = nextCK
            skippedMessageKeys[currentPeerKeyStr to receiveSequence] = mk
            receiveSequence++
        }
    }

    /**
     * Special initialization for Bob (Responder) after receiving PFS_INIT.
     */
    fun BobInit(peerPubKey: PublicKey) {
        peerRatchetPublicKey = peerPubKey
        val dhOut = E2EManager.calculateSharedSecret(myRatchetKeyPair.private, peerRatchetPublicKey!!)
        val (newRoot, newRecvCK) = E2EManager.kdfRoot(rootKey, dhOut)
        rootKey = newRoot
        receiveChainKey = newRecvCK
    }
}
