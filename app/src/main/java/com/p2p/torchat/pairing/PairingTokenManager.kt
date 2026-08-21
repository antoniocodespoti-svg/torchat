package com.p2p.torchat.pairing

import com.google.gson.Gson
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.model.Peer
import com.p2p.torchat.util.Constants
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.util.Base64

/**
 * Data structure for the signed pairing token.
 */
data class SignedToken(
    val v: Int = 1,
    val onion: String,
    val alias: String,
    val pubKey: String,
    val ts: Long,
    val nonce: Long,
    val sig: String
)

class PairingTokenManager {
    private val gson = Gson()

    /**
     * Generates a signed pairing token.
     */
    fun generateSignedToken(
        myOnion: String,
        myAlias: String,
        myPubKey: String,
        privateKey: PrivateKey
    ): String {
        val ts = System.currentTimeMillis()
        val nonce = java.security.SecureRandom().nextLong()

        // Canonical data to sign
        val dataToSign = "$myOnion|$myAlias|$myPubKey|$ts|$nonce"
        val signature = E2EManager.signData(dataToSign.toByteArray(StandardCharsets.UTF_8), privateKey)

        val tokenObj = SignedToken(
            onion = myOnion,
            alias = myAlias,
            pubKey = myPubKey,
            ts = ts,
            nonce = nonce,
            sig = Base64.getEncoder().encodeToString(signature)
        )

        return Base64.getEncoder().encodeToString(gson.toJson(tokenObj).toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Parses and verifies a signed pairing token.
     */
    fun parseAndVerifyToken(token: String): Peer? {
        return try {
            val json = String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8)
            val signedToken = gson.fromJson(json, SignedToken::class.java)

            // 1. Check expiry (e.g., 24 hours)
            val now = System.currentTimeMillis()
            if (now - signedToken.ts > 24 * 60 * 60 * 1000) return null

            // 2. Verify signature
            val dataToVerify = "${signedToken.onion}|${signedToken.alias}|${signedToken.pubKey}|${signedToken.ts}|${signedToken.nonce}"
            val pubKey = E2EManager.stringToPublicKey(signedToken.pubKey, Constants.ED25519_ALGO)
            val sigBytes = Base64.getDecoder().decode(signedToken.sig)

            if (E2EManager.verifySignature(dataToVerify.toByteArray(StandardCharsets.UTF_8), sigBytes, pubKey)) {
                Peer(
                    onionAddress = signedToken.onion,
                    alias = signedToken.alias,
                    identityPublicKey = signedToken.pubKey,
                    isVerified = false
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
