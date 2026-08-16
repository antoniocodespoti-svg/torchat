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
                        Log.d(TAG, "Attempt ${attempt + 1}: Connecting via Tor Proxy")
                        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksProxyHost, socksProxyPort))
                        val socket = Socket(proxy)
                        val targetPort = 80
                        val socketAddress = InetSocketAddress.createUnresolved(cleanOnion, targetPort)

                        socket.connect(socketAddress, timeoutMs)
                        val writer = PrintWriter(socket.getOutputStream(), true)

                        // Audit Point 12: Mathematically precise padding
                        val rawJson = gson.toJson(payload)
                        val paddedJson = addPrecisePadding(rawJson)

                        writer.println(paddedJson)
                        socket.close()
                        Log.d(TAG, "Payload transmission successful")
                        Result.success(true)
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "Attempt ${attempt + 1} failed")
                        Result.failure(e)
                    }
                }
            if (result.isSuccess) return Result.success(true)

            // Wait before retry
            if (attempt < 2) kotlinx.coroutines.delay(2000)
        }

        return Result.failure(lastError ?: Exception("Unknown error during Tor transmission"))
    }

    private fun addPrecisePadding(json: String): String {
        val buckets = listOf(4096, 131072, 524288, 1048576, 2097152, 5242880)
        val targetSize = buckets.find { it > json.length } ?: json.length
        if (json.length >= targetSize) return json

        // Append spaces to the end of JSON. GSON ignores trailing whitespace during parsing.
        val paddingNeeded = targetSize - json.length
        return json + " ".repeat(paddingNeeded)
    }

    private fun sanitizeOnion(onion: String): String {
        return onion.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removeSuffix("/")
    }
}
