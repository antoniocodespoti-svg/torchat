package com.p2p.supermaster.service

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

class TotpManager {
    companion object {
        const val TIME_STEP_MS = 86_400_000L // 24 Hours
        const val DIGITS_MASTER = 8

        // Tier 1: SuperMaster -> Master
        private const val T1_SECRET = "TorP2PSecure2026_T1_Y"
        const val SIGNATURE_HASH = "IT1nUJgd/gZVRshKf5EMa/PkwbRK1GiizWXocboLJt4="
    }

    /**
     * Generates a code for a Master (Tier 1).
     */
    fun generateMasterCode(
        masterOnion: String,
        networkTime: Long,
        durationDays: Int,
    ): String {
        val seed = deriveSeed(T1_SECRET, masterOnion, durationDays)
        return calculateTotp(seed, networkTime, DIGITS_MASTER)
    }

    private fun deriveSeed(
        baseSecret: String,
        onionAddress: String,
        duration: Int,
    ): ByteArray {
        val cleanOnion = onionAddress.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/").lowercase()
        val input = baseSecret + SIGNATURE_HASH + cleanOnion + duration.toString()
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    }

    private fun calculateTotp(
        seed: ByteArray,
        timeMillis: Long,
        digits: Int,
    ): String {
        val counter = timeMillis / TIME_STEP_MS
        val data = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(seed, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash[hash.size - 1].toInt() and 0xf
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 10.0.pow(digits.toDouble()).toLong()
        return otp.toString().padStart(digits, '0')
    }
}
