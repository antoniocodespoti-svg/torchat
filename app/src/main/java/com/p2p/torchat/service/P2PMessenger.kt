package com.p2p.torchat.service

import com.p2p.torchat.model.NetworkPayload
import com.p2p.torchat.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.random.Random

/**
 * Hardened P2PMessenger with Binary Framing (NET-001).
 * Now enforces binary transmission of encrypted payloads.
 */
class P2PMessenger(
    private val socksProxyHost: String = "127.0.0.1",
    private val socksProxyPort: Int = Constants.TOR_SOCKS_PORT,
) {
    companion object {
        private const val MAGIC_BYTE: Byte = 0x54 // 'T'
        private const val PROTOCOL_VERSION: Byte = 0x01
    }

    /**
     * Sends an already encrypted payload over Tor.
     */
    suspend fun sendEncryptedPayload(
        myOnion: String,
        recipientOnion: String,
        type: Byte,
        sequenceNumber: Int,
        ratchetPubKey: String,
        encryptedDataB64: String,
        timeoutMs: Int = 30000,
    ): Result<Boolean> {
        val cleanOnion = sanitizeOnion(recipientOnion)
        if (!cleanOnion.matches(Regex(Constants.ONION_V3_REGEX))) {
            return Result.failure(IllegalArgumentException("Invalid onion address: $cleanOnion"))
        }

        // Add random jitter to obscure traffic patterns
        kotlinx.coroutines.delay(Random.nextLong(100, 500))

        return withContext(Dispatchers.IO) {
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksProxyHost, socksProxyPort))
                val socket = Socket(proxy)
                socket.connect(InetSocketAddress.createUnresolved(cleanOnion, 80), timeoutMs)

                val dos = DataOutputStream(socket.getOutputStream())

                // Add bucketed padding to the base64 data to hide exact length
                val paddedData = addBucketedPadding(encryptedDataB64)
                val dataBytes = paddedData.toByteArray(Charsets.UTF_8)

                // 1. Write Binary Header
                dos.writeByte(MAGIC_BYTE.toInt())
                dos.writeByte(PROTOCOL_VERSION.toInt())
                dos.writeByte(type.toInt())
                dos.writeInt(sequenceNumber)

                // 2. Write Sender Onion
                val myOnionBytes = myOnion.toByteArray(Charsets.UTF_8)
                dos.writeInt(myOnionBytes.size)
                dos.write(myOnionBytes)

                // 3. Write Ratchet Public Key
                val rpkBytes = ratchetPubKey.toByteArray(Charsets.UTF_8)
                dos.writeInt(rpkBytes.size)
                dos.write(rpkBytes)

                // 4. Write Payload Length & Data
                dos.writeInt(dataBytes.size)
                dos.write(dataBytes)
                dos.flush()

                socket.close()
                Result.success(true)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun addBucketedPadding(data: String): String {
        // Standard bucket sizes to prevent traffic analysis on message length
        val buckets = listOf(1024, 4096, 16384, 65536, 262144, 1048576)
        val targetSize = buckets.find { it > data.length } ?: data.length
        return data.padEnd(targetSize, ' ')
    }

    private fun sanitizeOnion(o: String): String =
        o.trim()
            .removePrefix("http://").removePrefix("https://").removeSuffix("/")
}
