package com.p2p.tormaster.service

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object TotpManager {
    const val TIME_STEP_MS = 86_400_000L // 24 Hours
    const val DIGITS_CLIENT = 6
    const val DIGITS_MASTER = 8

    // Tier 2: Master -> Client
    private const val T2_SECRET = "TorP2PSecure2026_T2_X"

    // Tier 1: SuperMaster -> Master
    private const val T1_SECRET = "TorP2PSecure2026_T1_Y"

    const val SIGNATURE_HASH = "IT1nUJgd/gZVRshKf5EMa/PkwbRK1GiizWXocboLJt4="

    /**
     * Generates a code for a Client (Tier 2).
     */
    fun generateClientCode(
        onionAddress: String,
        networkTime: Long,
        durationDays: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val mac = Mac.getInstance("HmacSHA1")
        val seed = deriveSeed(digest, T2_SECRET, onionAddress, durationDays)
        return calculateTotp(mac, seed, networkTime, DIGITS_CLIENT)
    }

    /**
     * Generates a code for a Master (Tier 1).
     */
    fun generateMasterCode(
        masterOnion: String,
        networkTime: Long,
        durationDays: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val mac = Mac.getInstance("HmacSHA1")
        val seed = deriveSeed(digest, T1_SECRET, masterOnion, durationDays)
        return calculateTotp(mac, seed, networkTime, DIGITS_MASTER)
    }

    /**
     * Master-side validation: Tries to find which duration matches the recharge code.
     */
    fun findMatchingMasterDuration(
        inputCode: String,
        masterOnion: String,
        networkTime: Long,
    ): Int? {
        val digest = MessageDigest.getInstance("SHA-256")
        val mac = Mac.getInstance("HmacSHA1")
        val commonPacks = listOf(30, 90, 180, 365, 730, 1460)
        for (days in commonPacks) {
            if (inputCode == generateMasterCodeInternal(digest, mac, T1_SECRET, masterOnion, networkTime, days, DIGITS_MASTER)) return days
            if (inputCode == generateMasterCodeInternal(digest, mac, T1_SECRET, masterOnion, networkTime - TIME_STEP_MS, days, DIGITS_MASTER)) return days
        }
        for (days in 1..1500) {
            if (inputCode == generateMasterCodeInternal(digest, mac, T1_SECRET, masterOnion, networkTime, days, DIGITS_MASTER)) return days
            if (inputCode == generateMasterCodeInternal(digest, mac, T1_SECRET, masterOnion, networkTime - TIME_STEP_MS, days, DIGITS_MASTER)) return days
        }
        return null
    }

    private fun generateMasterCodeInternal(digest: MessageDigest, mac: Mac, baseSecret: String, onion: String, time: Long, duration: Int, digits: Int): String {
        val seed = deriveSeed(digest, baseSecret, onion, duration)
        return calculateTotp(mac, seed, time, digits)
    }

    private fun deriveSeed(
        digest: MessageDigest,
        baseSecret: String,
        onionAddress: String,
        duration: Int,
    ): ByteArray {
        val cleanOnion = onionAddress.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/").lowercase()
        val input = baseSecret + SIGNATURE_HASH + cleanOnion + duration.toString()
        digest.reset()
        return digest.digest(input.toByteArray())
    }

    private fun calculateTotp(
        mac: Mac,
        seed: ByteArray,
        timeMillis: Long,
        digits: Int,
    ): String {
        val counter = timeMillis / TIME_STEP_MS
        val data = ByteBuffer.allocate(8).putLong(counter).array()
        mac.reset()
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
