package com.p2p.torchat.model

import java.util.UUID

enum class PayloadType {
    CHAT_MESSAGE,
    SESSION_HANDSHAKE,
    SESSION_TERMINATE,
    PING,
    PONG,
    IMAGE,
    FILE,
    DUMMY_NOISE,
}

data class AttachmentMetadata(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String? = null,
)

data class Peer(
    val onionAddress: String,
    val alias: String,
    /** Base64 Ed25519 Identity Public Key */
    val identityPublicKey: String = "",
    val handshakePublicKey: String = "", // Legacy field for backward compat during migration
    val isVerified: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val isOnline: Boolean = false,
)

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val senderOnion: String,
    val recipientOnion: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = false,
    val isDelivered: Boolean = false,
    val isError: Boolean = false,
    val type: PayloadType = PayloadType.CHAT_MESSAGE,
    val attachment: AttachmentMetadata? = null,
    val sequenceNumber: Int = 0
)

data class NetworkPayload(
    val type: PayloadType = PayloadType.CHAT_MESSAGE,
    val senderOnion: String,
    val recipientOnion: String,
    val payloadData: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentMetadata: AttachmentMetadata? = null,
    val sequenceNumber: Int = 0,
    val sessionId: String = "",
    val padding: String = "",
)
