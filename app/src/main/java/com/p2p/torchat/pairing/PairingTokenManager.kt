package com.p2p.torchat.pairing

import android.content.Context
import com.google.gson.Gson
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.model.Peer
import com.p2p.torchat.util.Constants
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64

/**
 * Data structure for the signed pairing token.
 */
data class SignedToken(
    val v: Int = 2, // Version 2 with 128-bit nonce
    val onion: String,
    val alias: String,
    val pubKey: String,
    val ts: Long,
    val nonceB64: String,
    val sig: String
)

class PairingTokenManager(private val context: Context? = null) {
    private val gson = Gson()
    private val TOKEN_VALIDITY_MS = 5 * 60 * 1000L // 5 minutes (Audit 13)

    /**
     * Generates a signed pairing token with 128-bit nonce.
     */
    fun generateSignedToken(
        myOnion: String,
        myAlias: String,
        myPubKey: String,
        privateKey: PrivateKey
    ): String {
        val ts = System.currentTimeMillis()
        val nonce = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val nonceB64 = Base64.getEncoder().encodeToString(nonce)

        // Canonical data to sign
        val dataToSign = "$myOnion|$myAlias|$myPubKey|$ts|$nonceB64"
        val signature = E2EManager.signData(dataToSign.toByteArray(StandardCharsets.UTF_8), privateKey)

        val tokenObj = SignedToken(
            onion = myOnion,
            alias = myAlias,
            pubKey = myPubKey,
            ts = ts,
            nonceB64 = nonceB64,
            sig = Base64.getEncoder().encodeToString(signature)
        )

        return Base64.getEncoder().encodeToString(gson.toJson(tokenObj).toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Parses and verifies a signed pairing token.
     * Implements one-time use check via SharedPreferences.
     */
    fun parseAndVerifyToken(token: String): Peer? {
        return try {
            val json = String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8)
            val signedToken = gson.fromJson(json, SignedToken::class.java)

            // 1. Check expiry (5 minutes)
            val now = System.currentTimeMillis()
            if (now - signedToken.ts > TOKEN_VALIDITY_MS || signedToken.ts > now + 60000L) {
                return null
            }

            // 2. Verify signature BEFORE consuming nonce (Audit P1)
            val dataToVerify = "${signedToken.onion}|${signedToken.alias}|${signedToken.pubKey}|${signedToken.ts}|${signedToken.nonceB64}"
            val pubKey = E2EManager.stringToPublicKey(signedToken.pubKey, Constants.ED25519_ALGO)
            val sigBytes = Base64.getDecoder().decode(signedToken.sig)

            if (!E2EManager.verifySignature(dataToVerify.toByteArray(StandardCharsets.UTF_8), sigBytes, pubKey)) {
                return null
            }

            // 3. One-time use check (Audit Point 8)
            if (context != null) {
                val prefs = context.getSharedPreferences("pairing_prefs", Context.MODE_PRIVATE)
                if (prefs.contains(signedToken.nonceB64)) {
                    return null // Already used
                }
                prefs.edit().putBoolean(signedToken.nonceB64, true).apply()
            }

            return Peer(
                onionAddress = signedToken.onion,
                alias = signedToken.alias,
                identityPublicKey = signedToken.pubKey,
                isVerified = false
            )
        } catch (e: Exception) {
            null
        }
    }
}
