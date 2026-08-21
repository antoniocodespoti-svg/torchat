package com.p2p.torchat.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.crypto.SymmetricRatchetSession
import com.p2p.torchat.model.Message
import com.p2p.torchat.model.PayloadType
import com.p2p.torchat.model.Peer
import com.p2p.torchat.service.P2PMessenger
import com.p2p.torchat.service.TorManager
import com.p2p.torchat.service.TorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatRepository(
    private val p2pMessenger: P2PMessenger,
    private val torManager: TorManager
) {
    val activeSessions = mutableMapOf<String, SymmetricRatchetSession>()
    val messagesMap = mutableMapOf<String, MutableList<Message>>()
    val peersList = mutableListOf<Peer>()

    suspend fun sendMessage(peer: Peer, content: String): Result<Message> {
        val session = activeSessions[peer.onionAddress] ?: return Result.failure(Exception("No active session"))
        val myOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: return Result.failure(Exception("Tor not running"))

        val messageKey = session.nextSendKey()
        val aad = E2EManager.buildAAD(1, PayloadType.CHAT_MESSAGE.ordinal.toByte(), session.sendSequence, myOnion)
        val encrypted = E2EManager.encryptV2(content, messageKey, aad)

        val msg = Message(
            id = UUID.randomUUID().toString(),
            senderOnion = myOnion,
            recipientOnion = peer.onionAddress,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            type = PayloadType.CHAT_MESSAGE,
            sequenceNumber = session.sendSequence
        )

        val res = p2pMessenger.sendEncryptedPayload(myOnion, peer.onionAddress, PayloadType.CHAT_MESSAGE.ordinal.toByte(), session.sendSequence, encrypted)

        return if (res.isSuccess) {
            Result.success(msg.copy(isDelivered = true))
        } else {
            Result.success(msg.copy(isError = true))
        }
    }
}
