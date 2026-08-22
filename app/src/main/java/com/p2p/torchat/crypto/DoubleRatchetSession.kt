package com.p2p.torchat.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyPair
import java.security.PublicKey

/**
 * Full Double Ratchet Session implementation (v2.5).
 * Adheres to the Signal Double Ratchet specification.
 * Resolves Audit Point 2 & 3.
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

    private var nSend = 0 // Message Number for sending
    private var nRecv = 0 // Message Number for receiving
    private var pn = 0    // Previous sending chain length

    private val skippedMessageKeys = mutableMapOf<Pair<String, Int>, ByteArray>()
    private val MAX_SKIPPED_KEYS = 1000
    private val MAX_SKIP_GAP = 100

    data class RatchetHeader(
        val ratchetPublicKey: PublicKey,
        val pn: Int,
        val n: Int
    )

    data class SendResult(
        val messageKey: ByteArray,
        val header: RatchetHeader
    )

    init {
        // Initiator initialization: if we have peer's key, we can start the first sending chain
        if (peerRatchetPublicKey != null) {
            val dhOut = E2EManager.calculateSharedSecret(myRatchetKeyPair.private, peerRatchetPublicKey!!)
            val (newRoot, newSendCK) = E2EManager.kdfRoot(rootKey, dhOut)
            rootKey = newRoot
            sendChainKey = newSendCK
        }
    }

    /**
     * Advances the sending chain and returns a new Message Key and Ratchet Header.
     */
    suspend fun nextSendKey(): SendResult = mutex.withLock {
        if (sendChainKey == null) {
            // Initial DH for Responder or re-initialization
            if (peerRatchetPublicKey != null) {
                // When we create a new sending chain, we record the length of the previous one
                pn = nSend
                nSend = 0
                myRatchetKeyPair = E2EManager.generateEphemeralKeyPair()
                val dhOut = E2EManager.calculateSharedSecret(myRatchetKeyPair.private, peerRatchetPublicKey!!)
                val (newRoot, newSendCK) = E2EManager.kdfRoot(rootKey, dhOut)
                rootKey = newRoot
                sendChainKey = newSendCK
            } else {
                throw IllegalStateException("Ratchet not initialized with peer public key")
            }
        }

        val (nextCK, mk) = E2EManager.kdfChain(sendChainKey!!, "constant")
        sendChainKey = nextCK

        val header = RatchetHeader(myRatchetKeyPair.public, pn, nSend)
        val result = SendResult(mk, header)

        nSend++
        return@withLock result
    }

    /**
     * Attempts to decrypt a message using the Double Ratchet.
     */
    suspend fun tryDecrypt(
        header: RatchetHeader,
        encB64: String,
        aad: ByteArray,
        decryptFn: (String, ByteArray, ByteArray) -> String
    ): String = mutex.withLock {
        val pubKeyStr = E2EManager.publicKeyToString(header.ratchetPublicKey)

        // 1. Check skipped keys (out-of-order)
        skippedMessageKeys[pubKeyStr to header.n]?.let { key ->
            val decrypted = decryptFn(encB64, key, aad)
            skippedMessageKeys.remove(pubKeyStr to header.n)
            return@withLock decrypted
        }

        // 2. Perform DH Ratchet Step if peer sent a new public key
        if (header.ratchetPublicKey != peerRatchetPublicKey) {
            skipMessageKeys(header.pn) // Skip remaining keys in the PREVIOUS chain
            dhRatchetStep(header.ratchetPublicKey)
        }

        // 3. Skip messages in the CURRENT chain if necessary
        skipMessageKeys(header.n)

        // 4. Advance Symmetric Ratchet
        val (nextCK, mk) = E2EManager.kdfChain(receiveChainKey!!, "constant")

        // 5. Try to decrypt before committing state
        val decrypted = decryptFn(encB64, mk, aad)

        // 6. Commitment: Successful decryption, update state
        receiveChainKey = nextCK
        nRecv++

        return@withLock decrypted
    }

    private fun dhRatchetStep(newPeerPubKey: PublicKey) {
        pn = nSend
        nSend = 0
        nRecv = 0
        peerRatchetPublicKey = newPeerPubKey

        // Receiving Chain advancement
        val dhOutRecv = E2EManager.calculateSharedSecret(myRatchetKeyPair.private, peerRatchetPublicKey!!)
        val (rootAfterRecv, newRecvCK) = E2EManager.kdfRoot(rootKey, dhOutRecv)
        rootKey = rootAfterRecv
        receiveChainKey = newRecvCK

        // Sending Chain advancement
        myRatchetKeyPair = E2EManager.generateEphemeralKeyPair()
        val dhOutSend = E2EManager.calculateSharedSecret(myRatchetKeyPair.private, peerRatchetPublicKey!!)
        val (rootAfterSend, newSendCK) = E2EManager.kdfRoot(rootKey, dhOutSend)
        rootKey = rootAfterSend
        sendChainKey = newSendCK
    }

    private fun skipMessageKeys(untilN: Int) {
        if (receiveChainKey == null) return

        if (nRecv + (untilN - nRecv) > MAX_SKIPPED_KEYS) {
            throw SecurityException("Too many messages skipped (DoS protection)")
        }

        if (untilN - nRecv > MAX_SKIP_GAP) {
            throw SecurityException("Gap too large (DoS protection)")
        }

        val currentPeerKeyStr = E2EManager.publicKeyToString(peerRatchetPublicKey!!)
        while (nRecv < untilN) {
            val (nextCK, mk) = E2EManager.kdfChain(receiveChainKey!!, "constant")
            receiveChainKey = nextCK
            skippedMessageKeys[currentPeerKeyStr to nRecv] = mk
            nRecv++

            // Limit memory usage for skipped keys
            if (skippedMessageKeys.size > MAX_SKIPPED_KEYS) {
                val oldest = skippedMessageKeys.keys.first()
                skippedMessageKeys.remove(oldest)
            }
        }
    }

    /**
     * Initializer for Responder (Bob) after receiving the first handshake message.
     */
    fun BobInit(peerPubKey: PublicKey) {
        peerRatchetPublicKey = peerPubKey
        // Bob starts with a receiving chain derived from (Bob_EK, Alice_EK)
        val dhOut = E2EManager.calculateSharedSecret(myRatchetKeyPair.private, peerRatchetPublicKey!!)
        val (newRoot, newRecvCK) = E2EManager.kdfRoot(rootKey, dhOut)
        rootKey = newRoot
        receiveChainKey = newRecvCK
    }
}
