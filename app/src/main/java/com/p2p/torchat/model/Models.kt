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

enum class AuthMode {
    CREATE,
    LOGIN,
    CHANGE,
}

enum class SeedMode {
    DISPLAY,
    INPUT,
}

data class AttachmentMetadata(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String? = null,
)

data class Peer(
    val onionAddress: String,
    val alias: String,
    var identityPublicKey: String = "",
    var isVerified: Boolean = false,
    var lastSeenTimestamp: Long = System.currentTimeMillis(),
    var isOnline: Boolean = false,
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
    val sequenceNumber: Int = 0,
)

data class NetworkPayload(
    val id: String = UUID.randomUUID().toString(),
    val type: PayloadType = PayloadType.CHAT_MESSAGE,
    val senderOnion: String,
    val recipientOnion: String,
    val payloadData: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentMetadata: AttachmentMetadata? = null,
    val sequenceNumber: Int = 0,
    val sessionId: String = "",
)
