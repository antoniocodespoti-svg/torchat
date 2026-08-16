package com.p2p.torchat.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * RFC 5869 compliant HKDF-SHA256 implementation.
 * Resolves Audit Point 8.
 */
object HKDF {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    fun deriveKey(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray {
        val prk = extract(salt ?: ByteArray(HASH_LEN), ikm)
        return expand(prk, info ?: ByteArray(0), length)
    }

    private fun extract(
        salt: ByteArray,
        ikm: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    private fun expand(
        prk: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        val iterations = ceil(length.toDouble() / HASH_LEN).toInt()
        val okm = ByteArray(length)
        var lastT = ByteArray(0)
        var remaining = length

        for (i in 1..iterations) {
            mac.update(lastT)
            mac.update(info)
            mac.update(i.toByte())
            lastT = mac.doFinal()

            val stepSize = minOf(remaining, HASH_LEN)
            System.arraycopy(lastT, 0, okm, length - remaining, stepSize)
            remaining -= stepSize
        }
        return okm
    }
}
