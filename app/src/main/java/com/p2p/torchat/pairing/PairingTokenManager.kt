package com.p2p.torchat.pairing

import com.p2p.torchat.model.Peer
import java.security.SecureRandom
import java.util.Locale

data class TemporaryPairingToken(
    val code: String, // 8-character uppercase code (e.g. "X7K9M2P4")
    val hostOnionAddress: String,
    val hostAlias: String,
    val createdAtTimestamp: Long = System.currentTimeMillis(),
    val validityDurationMs: Long = 30_000L, // 30 seconds
) {
    fun isExpired(): Boolean {
        return (System.currentTimeMillis() - createdAtTimestamp) > validityDurationMs
    }

    fun getRemainingSeconds(): Int {
        val elapsed = System.currentTimeMillis() - createdAtTimestamp
        val remaining = (validityDurationMs - elapsed) / 1000
        return if (remaining > 0) remaining.toInt() else 0
    }
}

class PairingTokenManager {
    private val activeTokensMap = mutableMapOf<String, TemporaryPairingToken>()

    companion object {
        private const val CHAR_POOL = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /**
         * Encodes onion address + alias into an 8-character deterministic token representation
         */
        fun generateTokenCode(): String {
            val random = SecureRandom()
            val sb = StringBuilder(8)
            for (i in 0 until 8) {
                val index = random.nextInt(CHAR_POOL.length)
                sb.append(CHAR_POOL[index])
            }
            return sb.toString()
        }

        /**
         * Format display token with hyphen (e.g. "X7K9-M2P4")
         */
        fun formatTokenForDisplay(rawCode: String): String {
            val clean = rawCode.uppercase(Locale.ROOT).replace("-", "").replace(" ", "")
            return if (clean.length == 8) {
                "${clean.substring(0, 4)}-${clean.substring(4)}"
            } else {
                clean
            }
        }

        fun sanitizeTokenInput(input: String): String {
            return input.uppercase(Locale.ROOT).replace("-", "").replace(" ", "").trim()
        }
    }

    /**
     * Device A generates a temporary 8-character token valid for 30 seconds
     */
    fun createPairingToken(
        myOnionAddress: String,
        myAlias: String,
    ): TemporaryPairingToken {
        val code = generateTokenCode()
        val token =
            TemporaryPairingToken(
                code = code,
                hostOnionAddress = myOnionAddress,
                hostAlias = myAlias,
            )
        activeTokensMap[code] = token
        return token
    }

    /**
     * Device B enters the 8-character token. If valid and < 30s old, resolves Device A's Peer info.
     */
    fun resolveTokenAndGetPeer(rawCodeInput: String): Peer? {
        val sanitized = sanitizeTokenInput(rawCodeInput)
        val token = activeTokensMap[sanitized] ?: return null

        if (token.isExpired()) {
            activeTokensMap.remove(sanitized)
            return null
        }

        return Peer(
            onionAddress = token.hostOnionAddress,
            alias = token.hostAlias,
            handshakePublicKey = "",
        )
    }

    /**
     * Registers a known token (e.g. synchronized over local mesh/relay)
     */
    fun registerToken(token: TemporaryPairingToken) {
        if (!token.isExpired()) {
            activeTokensMap[token.code] = token
        }
    }

    fun cleanExpiredTokens() {
        activeTokensMap.entries.removeIf { it.value.isExpired() }
    }
}
