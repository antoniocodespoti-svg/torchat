package com.p2p.torchat.service

import android.util.Log
import com.google.gson.Gson
import com.p2p.torchat.model.NetworkPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.random.Random

class P2PMessenger(
    private val socksProxyHost: String = "127.0.0.1",
    private val socksProxyPort: Int = 9050,
) {
    companion object {
        private const val TAG = "P2PMessenger"
    }

    private val gson = Gson()

    /**
     * Connects to recipient's .onion address via local Tor SOCKS5 Proxy and transmits the payload
     * Includes automatic retry logic for transient network failures.
     */
    suspend fun sendPayloadOverTor(
        recipientOnion: String,
        payload: NetworkPayload,
        timeoutMs: Int = 45000,
    ): Result<Boolean> {
        val cleanOnion = sanitizeOnion(recipientOnion)
        var lastError: Exception? = null

        // Timing Jitter: Random delay between 100ms and 1000ms to obfuscate send patterns
        val jitter = Random.nextLong(100, 1000)
        kotlinx.coroutines.delay(jitter)

        repeat(3) { attempt ->
            val result =
                withContext(Dispatchers.IO) {
                    try {
                        Log.d(TAG, "Attempt ${attempt + 1}: Sending to $cleanOnion via Tor Proxy")
                        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksProxyHost, socksProxyPort))
                        val socket = Socket(proxy)
                        val targetPort = 80
                        val socketAddress = InetSocketAddress.createUnresolved(cleanOnion, targetPort)

                        socket.connect(socketAddress, timeoutMs)
                        val writer = PrintWriter(socket.getOutputStream(), true)

                        val paddedPayload = addPadding(payload)
                        val jsonPayload = gson.toJson(paddedPayload)

                        writer.println(jsonPayload)
                        socket.close()
                        Log.d(TAG, "Payload sent successfully to $cleanOnion (Size: ${jsonPayload.length} bytes)")
                        Result.success(true)
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "Attempt ${attempt + 1} failed for $cleanOnion: ${e.message}")
                        Result.failure(e)
                    }
                }
            if (result.isSuccess) return Result.success(true)

            // Wait before retry
            if (attempt < 2) kotlinx.coroutines.delay(2000)
        }

        return Result.failure(lastError ?: Exception("Unknown error during Tor transmission"))
    }

    private fun addPadding(payload: NetworkPayload): NetworkPayload {
        // Advanced Multi-Bucket Padding (obfuscates file/image sizes)
        val buckets =
            listOf(
                4096, // 4KB (Standard Text)
                131072, // 128KB (Large Text / Small Image)
                524288, // 512KB (Compressed Image)
                1048576, // 1MB (High-res Image)
                2097152, // 2MB (File)
                5242880, // 5MB (Max support)
            )

        val currentJson = gson.toJson(payload)
        val currentSize = currentJson.length

        // Find the next available bucket
        val targetSize = buckets.find { it > currentSize } ?: currentSize

        return if (currentSize < targetSize) {
            val paddingNeeded = targetSize - currentSize - 15 // Overhead for "padding":""
            if (paddingNeeded > 0) {
                // Generate padding string efficiently
                val paddingContent = StringBuilder(paddingNeeded)
                repeat(paddingNeeded) { paddingContent.append('x') }
                payload.copy(padding = paddingContent.toString())
            } else {
                payload
            }
        } else {
            payload
        }
    }

    private fun sanitizeOnion(onion: String): String {
        return onion.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removeSuffix("/")
    }
}
