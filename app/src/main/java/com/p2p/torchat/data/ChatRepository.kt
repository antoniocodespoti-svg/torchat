package com.p2p.torchat.data

import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.crypto.DoubleRatchetSession
import com.p2p.torchat.crypto.SessionManager
import com.p2p.torchat.model.Message
import com.p2p.torchat.model.PayloadType
import com.p2p.torchat.model.Peer
import com.p2p.torchat.service.P2PMessenger
import com.p2p.torchat.service.TorManager
import com.p2p.torchat.service.TorState
import java.nio.charset.StandardCharsets
import java.util.UUID

import java.util.concurrent.atomic.AtomicInteger

class ChatRepository(
    private val torManager: TorManager,
) {
    private val networkSequence = AtomicInteger(0)

    init {
        // Initialize from vault if already unlocked
        PrivacyController.vaultData.value?.networkSequence?.let {
            networkSequence.set(it)
        }
    }

    suspend fun sendMessage(peer: Peer, content: String): Result<Message> {
        val session = SessionManager.getSession(peer.onionAddress) ?: return Result.failure(Exception("No active session"))
        val myOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: return Result.failure(Exception("Tor not running"))

        return try {
            val sendResult = session.nextSendKey()
            val header = sendResult.header
            val rpkStr = E2EManager.publicKeyToString(header.ratchetPublicKey)

            val msgSeq = networkSequence.incrementAndGet()

            val aad = E2EManager.buildAAD(1, PayloadType.CHAT_MESSAGE.ordinal.toByte(), msgSeq, myOnion, session.sessionId, rpkStr, header.pn, header.n)
            val encrypted = try {
                E2EManager.encryptV2(content.toByteArray(StandardCharsets.UTF_8), sendResult.messageKey, aad)
            } finally {
                // Securely wipe message key after encryption (Audit P5)
                sendResult.messageKey.fill(0)
            }

            val msg = Message(
                id = UUID.randomUUID().toString(),
                senderOnion = myOnion,
                recipientOnion = peer.onionAddress,
                content = content,
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                type = PayloadType.CHAT_MESSAGE,
                sequenceNumber = msgSeq
            )

            // Fix FINDING-002: Durable Commit - Persist state BEFORE sending
            PrivacyController.updateVault { current ->
                val history = current.chatHistory.toMutableMap()
                val peerList = history.getOrDefault(peer.onionAddress, emptyList()).toMutableList()
                peerList.add(msg)
                history[peer.onionAddress] = peerList

                current.copy(
                    chatHistory = history,
                    sessionStates = SessionManager.getAllSessionsState(),
                    networkSequence = msgSeq
                )
            }

            val res = P2PMessenger.sendEncryptedPayload(
                myOnion = myOnion,
                recipientOnion = peer.onionAddress,
                type = PayloadType.CHAT_MESSAGE.ordinal.toByte(),
                sequenceNumber = msgSeq,
                ratchetPubKey = rpkStr,
                pn = header.pn,
                n = header.n,
                encryptedData = encrypted
            )

            if (res.isSuccess) {
                Result.success(msg.copy(isDelivered = true))
            } else {
                // We keep it in history as not delivered, but the ratchet has advanced.
                Result.success(msg.copy(isError = true))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
