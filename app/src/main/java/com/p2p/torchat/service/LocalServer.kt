package com.p2p.torchat.service

import android.util.Log
import com.google.gson.Gson
import com.p2p.torchat.model.NetworkPayload
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
 * Hardened LocalServer for Tor P2P communication.
 * Implements Binary Framing (NET-001) and DoS protection.
 */
class LocalServer(
    private val port: Int = 8080,
    private val onMessageReceived: (NetworkPayload) -> Unit,
) {
    companion object {
        private const val TAG = "LocalServer"
        private const val MAGIC_BYTE: Byte = 0x54 // 'T'
        private const val PROTOCOL_VERSION: Byte = 0x01
        private const val MAX_PAYLOAD_SIZE = 10 * 1024 * 1024 // 10MB
        private const val MAX_CONCURRENT_CONNECTIONS = 5
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val gson = Gson()
    private val activeConnections = AtomicInteger(0)

    fun startServer() {
        if (serverJob != null && serverJob!!.isActive) return

        serverJob =
            CoroutineScope(Dispatchers.IO).launch {
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
                socket.soTimeout = 30000
                socket.getInputStream().use { inputStream ->
                    val dis = DataInputStream(inputStream)

                    // 1. Read Magic Byte
                    if (dis.readByte() != MAGIC_BYTE) return@launch

                    // 2. Read Version
                    if (dis.readByte() != PROTOCOL_VERSION) return@launch

                    // 3. Read Type & Sequence
                    val typeByte = dis.readByte().toInt()
                    val seq = dis.readInt()

                    // Validate Type
                    if (typeByte < 0 || typeByte >= com.p2p.torchat.model.PayloadType.entries.size) return@launch

                    // 4. Read Length
                    val length = dis.readInt()
                    if (length <= 0 || length > MAX_PAYLOAD_SIZE) return@launch

                    // 5. Read Payload
                    val payloadBytes = ByteArray(length)
                    dis.readFully(payloadBytes)

                    val json = String(payloadBytes, Charsets.UTF_8).trim()
                    val payload = gson.fromJson(json, NetworkPayload::class.java)

                    // Validate sequence number matches header
                    if (payload.sequenceNumber != seq) return@launch

                    onMessageReceived(payload)
                }
            } catch (e: Exception) {
                // Silently drop invalid packets
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
