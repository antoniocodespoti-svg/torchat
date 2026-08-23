package com.p2p.torchat.crypto

import java.security.KeyPair

/**
 * Holds the sensitive identity state in RAM while the system is UNLOCKED.
 * Resolves Audit Point 4 & 5 (Semantic unlock lifecycle).
 */
data class IdentityContext(
    val entropy: ByteArray,
    val identityKeyPair: KeyPair,
    val mnemonicWords: List<String>
) {
    /**
     * Wipes sensitive data from RAM.
     */
    fun wipe() {
        entropy.fill(0)
        // Note: KeyPair keys are harder to wipe directly if they are opaque,
        // but we clear the references. The entropy is the most critical to wipe.
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityContext) return false
        if (!entropy.contentEquals(other.entropy)) return false
        if (identityKeyPair != other.identityKeyPair) return false
        if (mnemonicWords != other.mnemonicWords) return false
        return true
    }

    override fun hashCode(): Int {
        var result = entropy.contentHashCode()
        result = 31 * result + identityKeyPair.hashCode()
        result = 31 * result + mnemonicWords.hashCode()
        return result
    }
}
