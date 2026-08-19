package com.p2p.torchat.service

import com.google.gson.Gson
import com.p2p.torchat.model.NetworkPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.random.Random

/**
 * Hardened P2PMessenger with Binary Framing (NET-001).
 */
class P2PMessenger(
    private val socksProxyHost: String = "127.0.0.1",
    private val socksProxyPort: Int = 9050,
) {
    private val gson = Gson()

    companion object {
        private const val MAGIC_BYTE: Byte = 0x54 // 'T'
        private const val PROTOCOL_VERSION: Byte = 0x01
    }

    suspend fun sendPayloadOverTor(
        recipientOnion: String,
        payload: NetworkPayload,
        timeoutMs: Int = 30000,
    ): Result<Boolean> {
        val cleanOnion = sanitizeOnion(recipientOnion)
        kotlinx.coroutines.delay(Random.nextLong(100, 500))

        repeat(2) { attempt ->
            val result = withContext(Dispatchers.IO) {
                try {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksProxyHost, socksProxyPort))
                    val socket = Socket(proxy)
                    socket.connect(InetSocketAddress.createUnresolved(cleanOnion, 80), timeoutMs)

                    val dos = DataOutputStream(socket.getOutputStream())
                    val json = addBucketedPadding(gson.toJson(payload))
                    val jsonBytes = json.toByteArray(Charsets.UTF_8)

                    // Write Binary Header
                    dos.writeByte(MAGIC_BYTE.toInt())
                    dos.writeByte(PROTOCOL_VERSION.toInt())
                    dos.writeByte(payload.type.ordinal)
                    dos.writeInt(payload.sequenceNumber)
                    dos.writeInt(jsonBytes.size)

                    // Write Payload
                    dos.write(jsonBytes)
                    dos.flush()
                    socket.close()
                    Result.success(true)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (result.isSuccess) return Result.success(true)
            if (attempt < 1) kotlinx.coroutines.delay(2000)
        }
        return Result.failure(Exception("Tor delivery failed"))
    }

    private fun addBucketedPadding(json: String): String {
        val buckets = listOf(4096, 65536, 262144, 1048576, 5242880)
        val targetSize = buckets.find { it > json.length } ?: json.length
        return json.padEnd(targetSize, ' ')
    }

    private fun sanitizeOnion(o: String): String = o.trim()
        .removePrefix("http://").removePrefix("https://").removeSuffix("/")
}
