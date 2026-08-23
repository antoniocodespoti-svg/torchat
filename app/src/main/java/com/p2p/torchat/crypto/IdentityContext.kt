package com.p2p.torchat.crypto

import java.security.KeyPair

/**
 * Holds the sensitive identity state in RAM while the system is UNLOCKED.
 * Resolves Audit Point 4 & 5 (Semantic unlock lifecycle).
 * Updated in v7: Removed mnemonic words to minimize secret lifetime.
 */
data class IdentityContext(
    val entropy: ByteArray,
    val identityKeyPair: KeyPair
) {
    /**
     * Wipes sensitive data from RAM.
     */
    fun wipe() {
        entropy.fill(0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityContext) return false
        if (!entropy.contentEquals(other.entropy)) return false
        if (identityKeyPair != other.identityKeyPair) return false
        return true
    }

    override fun hashCode(): Int {
        var result = entropy.contentHashCode()
        result = 31 * result + identityKeyPair.hashCode()
        return result
    }
}
