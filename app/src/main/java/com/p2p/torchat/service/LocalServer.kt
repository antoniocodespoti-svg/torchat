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
    val dataB64: String,
    val senderAddress: String // IP/Host of the incoming connection
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
        private const val MAX_PAYLOAD_SIZE = 1 * 1024 * 1024 // Reduced to 1MB to prevent DoS (Audit 12)
        private const val MAX_ONION_LENGTH = 128
        private const val MAX_CONCURRENT_CONNECTIONS = 5
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeConnections = AtomicInteger(0)

    fun startServer() {
        if (serverJob != null && serverJob!!.isActive) return

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
                if (onionLen <= 0 || onionLen > MAX_ONION_LENGTH) {
                    return@launch
                }

                // Increase timeout for payload reading
                socket.soTimeout = 30000

                // 5. Read Sender Onion
                val onionBytes = ByteArray(onionLen)
                dis.readFully(onionBytes)
                val senderOnion = String(onionBytes, Charsets.UTF_8)

                if (!senderOnion.matches(Regex(Constants.ONION_V3_REGEX))) {
                    return@launch
                }

                // 6. Read Payload Length
                val length = dis.readInt()
                if (length <= 0 || length > MAX_PAYLOAD_SIZE) {
                    return@launch
                }

                // 7. Read Payload
                val payloadBytes = ByteArray(length)
                dis.readFully(payloadBytes)

                val data = String(payloadBytes, Charsets.UTF_8).trim()

                onPacketReceived(
                    RawPacket(
                        version = version,
                        type = type,
                        sequenceNumber = seq,
                        senderOnion = senderOnion,
                        dataB64 = data,
                        senderAddress = socket.inetAddress.hostAddress ?: "unknown"
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
