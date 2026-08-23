package com.p2p.torchat.crypto

import android.content.Context
import com.google.gson.Gson
import com.p2p.torchat.model.Peer
import com.p2p.torchat.util.Constants
import com.p2p.torchat.util.Logger
import java.io.File
import javax.crypto.SecretKey

/**
 * Unified Secure Vault for all persistent sensitive data.
 * Resolves Audit Point 13 & 20 (Metadata leakage, password change atomicity).
 */
data class VaultData(
    val passwordHash: String? = null,
    val masterEntropy: ByteArray? = null, // Added in v7: Root of all identity
    val peers: List<Peer> = emptyList(),
    val myOnion: String? = null,
    val myPublicKey: String? = null,
    val myAlias: String = "Amico",
    val isDarkTheme: Boolean = true,
    val isAutoBackupEnabled: Boolean = false,
    val isAvailable: Boolean = false,
    val expiryDate: Long = 0L,
    val failedAttempts: Int = 0,
    val isTermsAccepted: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultData) return false
        if (passwordHash != other.passwordHash) return false
        if (!((masterEntropy == null && other.masterEntropy == null) || (masterEntropy != null && other.masterEntropy != null && masterEntropy.contentEquals(other.masterEntropy)))) return false
        if (peers != other.peers) return false
        if (myOnion != other.myOnion) return false
        if (myPublicKey != other.myPublicKey) return false
        if (myAlias != other.myAlias) return false
        if (isDarkTheme != other.isDarkTheme) return false
        if (isAutoBackupEnabled != other.isAutoBackupEnabled) return false
        if (isAvailable != other.isAvailable) return false
        if (expiryDate != other.expiryDate) return false
        if (failedAttempts != other.failedAttempts) return false
        if (isTermsAccepted != other.isTermsAccepted) return false
        return true
    }

    override fun hashCode(): Int {
        var result = passwordHash?.hashCode() ?: 0
        result = 31 * result + (masterEntropy?.contentHashCode() ?: 0)
        result = 31 * result + peers.hashCode()
        result = 31 * result + (myOnion?.hashCode() ?: 0)
        result = 31 * result + (myPublicKey?.hashCode() ?: 0)
        result = 31 * result + myAlias.hashCode()
        result = 31 * result + isDarkTheme.hashCode()
        result = 31 * result + isAutoBackupEnabled.hashCode()
        result = 31 * result + isAvailable.hashCode()
        result = 31 * result + expiryDate.hashCode()
        result = 31 * result + failedAttempts
        result = 31 * result + isTermsAccepted.hashCode()
        return result
    }
}

object SecureVault {
    private const val VAULT_FILE = "vault.json.enc"
    private val gson = Gson()

    /**
     * Saves the vault data atomically.
     */
    fun save(context: Context, data: VaultData, key: SecretKey) {
        val json = gson.toJson(data)
        val encrypted = E2EManager.encrypt(json, key)

        val vaultFile = File(context.filesDir, VAULT_FILE)
        val tempFile = File(context.filesDir, "$VAULT_FILE.tmp")

        try {
            tempFile.writeText(encrypted)

            // Verify readability before renaming (Atomic Commit)
            val testRead = tempFile.readText()
            if (testRead != encrypted) throw IllegalStateException("Vault write verification failed")

            if (!tempFile.renameTo(vaultFile)) {
                // On some systems renameTo fails if target exists
                vaultFile.delete()
                if (!tempFile.renameTo(vaultFile)) throw IllegalStateException("Vault rename failed")
            }
            Logger.i("SecureVault: Saved successfully")
        } catch (e: Exception) {
            Logger.e("SecureVault: Save failed", e)
            throw e
        }
    }

    /**
     * Loads the vault data.
     */
    fun load(context: Context, key: SecretKey): VaultData? {
        val vaultFile = File(context.filesDir, VAULT_FILE)
        if (!vaultFile.exists()) return null

        return try {
            val encrypted = vaultFile.readText()
            val json = E2EManager.decrypt(encrypted, key)
            gson.fromJson(json, VaultData::class.java)
        } catch (e: Exception) {
            Logger.e("SecureVault: Load failed (likely wrong password)", e)
            null
        }
    }

    /**
     * Securely destroys the vault file.
     */
    fun destroy(context: Context) {
        val vaultFile = File(context.filesDir, VAULT_FILE)
        val tempFile = File(context.filesDir, "$VAULT_FILE.tmp")

        if (vaultFile.exists()) {
            // Overwrite with zeros before deleting (best effort)
            vaultFile.writeText("0".repeat(vaultFile.length().toInt()))
            vaultFile.delete()
        }
        if (tempFile.exists()) tempFile.delete()
        Logger.w("SecureVault: Destroyed")
    }
}
