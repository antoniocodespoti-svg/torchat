package com.p2p.torchat.service

import android.util.Log
import com.google.gson.Gson
import com.p2p.torchat.model.NetworkPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class LocalServer(
    private val port: Int = 8080,
    private val onMessageReceived: (NetworkPayload) -> Unit,
) {
    companion object {
        private const val TAG = "LocalServer"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val gson = Gson()

    fun startServer() {
        if (serverJob != null && serverJob!!.isActive) return

        serverJob =
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d(TAG, "Starting LocalServer on port $port...")
                    serverSocket = ServerSocket(port)
                    Log.d(TAG, "LocalServer listening for connections...")
                    while (isActive) {
                        val clientSocket = serverSocket?.accept() ?: break
                        Log.d(TAG, "New incoming connection from ${clientSocket.inetAddress}")
                        handleClient(clientSocket)
                    }
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "Server error: ${e.message}")
                }
            }
    }

    private fun handleClient(socket: Socket) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val rawJson = reader.readLine()
                if (!rawJson.isNullOrEmpty()) {
                    Log.d(TAG, "Payload received: ${rawJson.take(100)}...")
                    val payload = gson.fromJson(rawJson, NetworkPayload::class.java)
                    onMessageReceived(payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client handling error: ${e.message}")
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    // Ignore close failure
                }
            }
        }
    }

    fun stopServer() {
        try {
            serverSocket?.close()
            serverJob?.cancel()
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Error stopping server: ${e.message}")
        }
    }
}
