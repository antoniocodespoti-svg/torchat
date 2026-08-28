package com.p2p.torchat.service

import android.util.Log
import com.p2p.torchat.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Raw packet received from the network.
 */
data class RawPacket(
    val version: Byte,
    val type: Byte,
    val sequenceNumber: Int,
    val senderOnion: String,
    val ratchetPubKey: String,
    val pn: Int,
    val n: Int,
    val data: ByteArray
)

/**
 * Hardened LocalServer for Tor P2P communication.
 * Implements Binary Framing (NET-001) and DoS protection.
 */
class LocalServer(
    private val port: Int = Constants.LOCAL_SERVER_PORT,
    private val onPacketReceived: (RawPacket) -> Unit,
) {
    companion object {
        private const val TAG = "LocalServer"
        private const val MAGIC_BYTE: Byte = 0x54 // 'T'
        private const val MAX_PAYLOAD_SIZE = 1 * 1024 * 1024 // 1MB limit
        private const val MAX_ONION_LENGTH = 128
        private const val MAX_RPK_LENGTH = 1024
        private const val MAX_CONCURRENT_CONNECTIONS = 5
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeConnections = AtomicInteger(0)

    fun startServer() {
        if (serverJob != null && serverJob?.isActive == true) return

        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress("127.0.0.1", port))

                while (isActive) {
                    val clientSocket = serverSocket?.accept() ?: break
                    if (activeConnections.get() >= MAX_CONCURRENT_CONNECTIONS) {
                        clientSocket.close()
                        continue
                    }
                    handleClient(clientSocket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        activeConnections.incrementAndGet()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Initial short timeout for header to prevent slow-client DoS
                socket.soTimeout = 5000
                val dis = DataInputStream(socket.getInputStream())

                // 1. Read Magic Byte
                if (dis.readByte() != MAGIC_BYTE) {
                    return@launch
                }

                // 2. Read Version
                val version = dis.readByte()
                if (version != 0x01.toByte()) {
                    return@launch
                }

                // 3. Read Type & Sequence
                val type = dis.readByte()
                val seq = dis.readInt()

                // 4. Read Sender Onion Length
                val onionLen = dis.readInt()
                if (onionLen !in 1..MAX_ONION_LENGTH) {
                    Log.w(TAG, "Onion length too large: $onionLen")
                    return@launch
                }

                // Increase timeout for payload reading
                socket.soTimeout = 30000

                // 5. Read Sender Onion
                val onionBytes = ByteArray(onionLen)
                dis.readFully(onionBytes)
                val senderOnion = String(onionBytes, Charsets.UTF_8)

                if (!senderOnion.matches(Regex(Constants.ONION_V3_REGEX))) {
                    Log.w(TAG, "Invalid Onion format: $senderOnion")
                    return@launch
                }

                // 6. Read Ratchet Public Key Length
                val pubKeyLen = dis.readInt()
                if (pubKeyLen !in 0..MAX_RPK_LENGTH) {
                    Log.w(TAG, "Invalid ratchet key length: $pubKeyLen")
                    return@launch
                }

                // P0 Fix: Some packet types (handshake, pong) don't require a ratchet key
                val requiresRPK = type == com.p2p.torchat.model.PayloadType.CHAT_MESSAGE.ordinal.toByte() ||
                                 type == com.p2p.torchat.model.PayloadType.IMAGE.ordinal.toByte() ||
                                 type == com.p2p.torchat.model.PayloadType.FILE.ordinal.toByte()

                if (requiresRPK && pubKeyLen == 0) {
                    Log.w(TAG, "Ratchet key required for type $type but length is 0")
                    return@launch
                }

                // 7. Read Ratchet Public Key
                val ratchetPubKey = if (pubKeyLen > 0) {
                    val pubKeyBytes = ByteArray(pubKeyLen)
                    dis.readFully(pubKeyBytes)
                    String(pubKeyBytes, Charsets.UTF_8)
                } else {
                    ""
                }

                // 8. Read PN & N (Double Ratchet Compliance)
                val pn = dis.readInt()
                val n = dis.readInt()

                if (pn !in 0..10000 || n !in 0..10000) {
                    Log.w(TAG, "Invalid DR counters: pn=$pn, n=$n")
                    return@launch
                }

                // 9. Read Payload Lengths
                val realLength = dis.readInt()
                val bucketedLength = dis.readInt()

                if (bucketedLength !in 1..MAX_PAYLOAD_SIZE) {
                    Log.w(TAG, "Invalid payload length: $bucketedLength")
                    return@launch
                }

                if (realLength !in 1..bucketedLength) {
                    Log.w(TAG, "Invalid real length: $realLength (bucketed: $bucketedLength)")
                    return@launch
                }

                // 10. Read Padded Payload
                val paddedBytes = ByteArray(bucketedLength)
                try {
                    dis.readFully(paddedBytes)
                } catch (e: java.io.EOFException) {
                    Log.w(TAG, "Incomplete payload received from ${socket.inetAddress}")
                    return@launch
                }

                // 11. Strip Padding
                val payloadBytes = paddedBytes.sliceArray(0 until realLength)

                if (payloadBytes.isEmpty()) return@launch

                onPacketReceived(
                    RawPacket(
                        version = version,
                        type = type,
                        sequenceNumber = seq,
                        senderOnion = senderOnion,
                        ratchetPubKey = ratchetPubKey,
                        pn = pn,
                        n = n,
                        data = payloadBytes
                    )
                )

            } catch (e: Exception) {
                // Silently drop invalid/malformed packets to prevent info leakage
            } finally {
                activeConnections.decrementAndGet()
                try {
                    socket.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    fun stopServer() {
        try {
            serverSocket?.close()
            serverJob?.cancel()
        } catch (e: Exception) {
        }
    }
}
