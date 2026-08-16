package com.p2p.tormaster.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

object HKDF {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    fun deriveKey(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray?,
        length: Int,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(salt ?: ByteArray(HASH_LEN), HMAC_ALGORITHM))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
        val iterations = ceil(length.toDouble() / HASH_LEN).toInt()
        val okm = ByteArray(length)
        var lastT = ByteArray(0)
        var remaining = length

        for (i in 1..iterations) {
            mac.update(lastT)
            info?.let { mac.update(it) }
            mac.update(i.toByte())
            lastT = mac.doFinal()
            val stepSize = minOf(remaining, HASH_LEN)
            System.arraycopy(lastT, 0, okm, length - remaining, stepSize)
            remaining -= stepSize
        }
        return okm
    }
}
