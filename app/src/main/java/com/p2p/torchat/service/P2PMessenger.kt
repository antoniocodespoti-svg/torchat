package com.p2p.torchat.service

import com.google.gson.Gson
import com.p2p.torchat.model.NetworkPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.random.Random

/**
 * Hardened P2PMessenger for Tor delivery.
 * Implements Jitter, Bucketed Padding, and automatic retries.
 */
class P2PMessenger(
    private val socksProxyHost: String = "127.0.0.1",
    private val socksProxyPort: Int = 9050,
) {
    private val gson = Gson()

    suspend fun sendPayloadOverTor(
        recipientOnion: String,
        payload: NetworkPayload,
        timeoutMs: Int = 30000,
    ): Result<Boolean> {
        val cleanOnion = sanitizeOnion(recipientOnion)

        // Anti-Traffic Analysis Jitter (Audit Point 9)
        kotlinx.coroutines.delay(Random.nextLong(100, 800))

        repeat(2) { attempt ->
            val result = withContext(Dispatchers.IO) {
                try {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksProxyHost, socksProxyPort))
                    val socket = Socket(proxy)
                    socket.connect(InetSocketAddress.createUnresolved(cleanOnion, 80), timeoutMs)

                    val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                    val rawJson = gson.toJson(payload)
                    val paddedJson = addBucketedPadding(rawJson)

                    writer.write(paddedJson)
                    writer.flush()
                    socket.close()
                    Result.success(true)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (result.isSuccess) return Result.success(true)
            if (attempt < 1) kotlinx.coroutines.delay(2000)
        }
        return Result.failure(Exception("Transmission failed after retries"))
    }

    private fun addBucketedPadding(json: String): String {
        // Standardized sizes to prevent length identification
        val buckets = listOf(4096, 32768, 131072, 524288, 1048576, 5242880)
        val targetSize = buckets.find { it > json.length } ?: json.length
        return json.padEnd(targetSize, ' ')
    }

    private fun sanitizeOnion(o: String): String = o.trim()
        .removePrefix("http://").removePrefix("https://").removeSuffix("/")
}
