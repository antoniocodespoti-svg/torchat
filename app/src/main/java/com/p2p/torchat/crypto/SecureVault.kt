package com.p2p.torchat.crypto

import android.content.Context
import com.google.gson.Gson
import com.p2p.torchat.model.Peer
import com.p2p.torchat.util.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.SecretKey

/**
 * Unified Secure Vault for persistent metadata (No secrets).
 * Resolves Audit Point 5 & 13 (Secret/Metadata separation).
 * Updated in v8: Removed masterEntropy and passwordHash.
 */
data class VaultData(
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
)

/**
 * Internal container for persistence.
 * Combines metadata (VaultData) and the root secret (masterEntropy).
 */
internal data class VaultEnvelope(
    val metadata: VaultData,
    val masterEntropy: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultEnvelope) return false
        if (metadata != other.metadata) return false
        if (!masterEntropy.contentEquals(other.masterEntropy)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + masterEntropy.contentHashCode()
        return result
    }
}

object SecureVault {
    private const val VAULT_FILE = "vault.json.enc"
    private const val BACKUP_FILE = "vault.json.enc.bak"
    private val gson = Gson()

    /**
     * Saves metadata and entropy atomically.
     * Resolves Audit Point 7 (Atomic Commit).
     */
    fun save(context: Context, data: VaultData, entropy: ByteArray, key: SecretKey) {
        val envelope = VaultEnvelope(data, entropy)
        val json = gson.toJson(envelope)
        val encrypted = E2EManager.encrypt(json, key)

        val vaultFile = File(context.filesDir, VAULT_FILE)
        val tempFile = File(context.filesDir, "$VAULT_FILE.tmp")
        val backupFile = File(context.filesDir, BACKUP_FILE)

        try {
            // 1. Write to temporary file
            tempFile.writeText(encrypted)

            // 2. Verify readability
            val testRead = tempFile.readText()
            if (testRead != encrypted) throw IllegalStateException("Vault write verification failed")

            // 3. Keep current as backup if it exists
            if (vaultFile.exists()) {
                Files.move(vaultFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            // 4. Atomic Move (API 26+)
            Files.move(tempFile.toPath(), vaultFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

            Logger.i("SecureVault: Atomic save successful")
        } catch (e: Exception) {
            Logger.e("SecureVault: Atomic save failed", e)
            // Rollback backup if possible
            if (backupFile.exists() && !vaultFile.exists()) {
                backupFile.renameTo(vaultFile)
            }
            throw e
        }
    }

    /**
     * Loads the vault and returns metadata and entropy.
     */
    fun load(context: Context, key: SecretKey): Pair<VaultData, ByteArray>? {
        val vaultFile = File(context.filesDir, VAULT_FILE)
        if (!vaultFile.exists()) return null

        return try {
            val encrypted = vaultFile.readText()
            val json = E2EManager.decrypt(encrypted, key)
            val envelope = gson.fromJson(json, VaultEnvelope::class.java)
            envelope.metadata to envelope.masterEntropy
        } catch (e: Exception) {
            Logger.e("SecureVault: Load failed")
            null
        }
    }

    /**
     * Securely destroys the vault files.
     */
    fun destroy(context: Context) {
        val files = listOf(VAULT_FILE, BACKUP_FILE, "$VAULT_FILE.tmp")
        files.forEach { name ->
            val f = File(context.filesDir, name)
            if (f.exists()) {
                f.writeText("0".repeat(f.length().toInt().coerceAtLeast(1)))
                f.delete()
            }
        }
        Logger.w("SecureVault: Destroyed")
    }
}
