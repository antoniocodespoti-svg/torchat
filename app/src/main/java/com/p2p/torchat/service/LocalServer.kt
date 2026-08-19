package com.p2p.torchat.service

import android.util.Log
import com.google.gson.Gson
import com.p2p.torchat.model.NetworkPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hardened LocalServer for Tor P2P communication.
 * Implements DoS protection, input limits and concurrent connection management.
 */
class LocalServer(
    private val port: Int = 8080,
    private val onMessageReceived: (NetworkPayload) -> Unit,
) {
    companion object {
        private const val TAG = "LocalServer"
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val MAX_PAYLOAD_SIZE = 10 * 1024 * 1024 // 10MB limit
        private const val MAX_CONCURRENT_CONNECTIONS = 5
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val gson = Gson()
    private val activeConnections = AtomicInteger(0)

    fun startServer() {
        if (serverJob != null && serverJob!!.isActive) return

        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket()
                serverSocket?.reuseAddress = true
                serverSocket?.bind(InetSocketAddress(LOOPBACK_ADDRESS, port))

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
                socket.soTimeout = 30000 // 30s timeout for reading
                val inputStream = socket.getInputStream()
                val rawData = readWithLimit(inputStream, MAX_PAYLOAD_SIZE)

                if (rawData.isNotEmpty()) {
                    val json = String(rawData, Charsets.UTF_8).trim()
                    if (json.startsWith("{") && json.endsWith("}")) {
                        val payload = gson.fromJson(json, NetworkPayload::class.java)
                        onMessageReceived(payload)
                    }
                }
            } catch (e: Exception) {
                // Fail silently to avoid leaking info in logs
            } finally {
                activeConnections.decrementAndGet()
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }

    private fun readWithLimit(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(8192)
        val output = java.io.ByteArrayOutputStream()
        var totalRead = 0
        while (totalRead < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - totalRead))
            if (read <= 0) break
            output.write(buffer, 0, read)
            totalRead += read
        }
        return output.toByteArray()
    }

    fun stopServer() {
        try {
            serverSocket?.close()
            serverJob?.cancel()
        } catch (e: Exception) {}
    }
}
