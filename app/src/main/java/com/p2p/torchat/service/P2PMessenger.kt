package com.p2p.torchat.service

import com.p2p.torchat.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Hardened P2PMessenger with Binary Framing (NET-001).
 * Now enforces binary transmission of encrypted payloads.
 */
object P2PMessenger {
    private const val MAGIC_BYTE: Byte = 0x54 // 'T'
    private const val PROTOCOL_VERSION: Byte = 0x01

    /**
     * Sends an already encrypted payload over Tor.
     */
    suspend fun sendEncryptedPayload(
        myOnion: String,
        recipientOnion: String,
        type: Byte,
        sequenceNumber: Int,
        ratchetPubKey: String,
        pn: Int,
        n: Int,
        encryptedData: ByteArray,
        timeoutMs: Int = 30000,
        socksProxyHost: String = "127.0.0.1",
        socksProxyPort: Int = Constants.TOR_SOCKS_PORT,
    ): Result<Boolean> {
        val cleanOnion = sanitizeOnion(recipientOnion)
        if (!cleanOnion.matches(Regex(Constants.ONION_V3_REGEX))) {
            return Result.failure(IllegalArgumentException("Invalid onion address: $cleanOnion"))
        }

        // Limit field lengths to prevent DoS on the receiver
        if (myOnion.length > 128) return Result.failure(IllegalArgumentException("My Onion too long"))
        if (ratchetPubKey.length > 1024) return Result.failure(IllegalArgumentException("Ratchet key too long"))

        // Add random jitter to obscure traffic patterns
        kotlinx.coroutines.delay(Random.nextLong(100, 500).milliseconds)

        return withContext(Dispatchers.IO) {
            try {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksProxyHost, socksProxyPort))
                val socket = Socket(proxy)
                socket.connect(InetSocketAddress.createUnresolved(cleanOnion, 80), timeoutMs)

                val dos = DataOutputStream(socket.getOutputStream())

                // Add bucketed padding to the binary data to hide exact length
                val dataBytes = addBucketedPadding(encryptedData)

                if (dataBytes.size > 1048576) {
                    return@withContext Result.failure(IllegalArgumentException("Payload exceeds 1MB limit"))
                }

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

                // 4. Write PN & N
                dos.writeInt(pn)
                dos.writeInt(n)

                // 5. Write Payload Length & Data
                dos.writeInt(dataBytes.size)
                dos.write(dataBytes)
                dos.flush()

                socket.close()
                Result.success(value = true)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun addBucketedPadding(data: ByteArray): ByteArray {
        // Standard bucket sizes to prevent traffic analysis on message length
        val buckets = listOf(1024, 4096, 16384, 65536, 262144, 1048576)
        val targetSize = buckets.find { it > data.size } ?: data.size
        if (targetSize <= data.size) return data

        val padded = ByteArray(targetSize)
        System.arraycopy(data, 0, padded, 0, data.size)
        // Fill the rest with random bytes (padding)
        val paddingSize = targetSize - data.size
        val padding = ByteArray(paddingSize).apply { Random.nextBytes(this) }
        System.arraycopy(padding, 0, padded, data.size, paddingSize)
        return padded
    }

    private fun sanitizeOnion(o: String): String =
        o.trim()
            .removePrefix("http://").removePrefix("https://").removeSuffix("/")
}
