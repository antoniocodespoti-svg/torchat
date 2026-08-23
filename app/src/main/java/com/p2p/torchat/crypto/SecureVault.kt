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
 * Updated in v9: Regular class to avoid accidental copying/logging.
 */
internal class VaultEnvelope(
    val metadata: VaultData,
    val masterEntropy: ByteArray
)

object SecureVault {
    private const val VAULT_FILE = "vault.json.enc"
    private const val PENDING_FILE = "vault.json.enc.pending"
    private val gson = Gson()

    /**
     * Saves metadata and entropy using a journaling strategy.
     * Resolves Audit Point 7, 11 & 13 (Atomic Commit, Crash safety).
     */
    fun save(context: Context, data: VaultData, entropy: ByteArray, key: SecretKey) {
        val envelope = VaultEnvelope(data, entropy)
        val json = gson.toJson(envelope)
        val encrypted = E2EManager.encrypt(json, key)

        val vaultFile = File(context.filesDir, VAULT_FILE)
        val pendingFile = File(context.filesDir, PENDING_FILE)

        try {
            // 1. Write to pending file
            pendingFile.writeText(encrypted)

            // 2. Verify readability and integrity
            val testRead = pendingFile.readText()
            if (testRead != encrypted) throw IllegalStateException("Verification failed")

            // 3. Atomic Move (API 26+)
            Files.move(
                pendingFile.toPath(),
                vaultFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )

            Logger.i("SecureVault: Save committed")
        } catch (e: Exception) {
            // Sanitize log: No exception object in production (Audit Point 14)
            Logger.e("SecureVault: Atomic commit failed")
            if (pendingFile.exists()) pendingFile.delete()
            throw e
        }
    }

    /**
     * Loads the vault. Handles recovery from pending file if necessary.
     */
    fun load(context: Context, key: SecretKey): Pair<VaultData, ByteArray>? {
        val vaultFile = File(context.filesDir, VAULT_FILE)
        val pendingFile = File(context.filesDir, PENDING_FILE)

        // If vault doesn't exist but pending does, attempt recovery
        if (!vaultFile.exists() && pendingFile.exists()) {
            Logger.w("SecureVault: Recovering from pending transaction")
            pendingFile.renameTo(vaultFile)
        }

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
     * Securely destroys all vault-related files.
     */
    fun destroy(context: Context) {
        val names = listOf(VAULT_FILE, PENDING_FILE, "vault.json.enc.bak") // Cleanup legacy too
        names.forEach { name ->
            val f = File(context.filesDir, name)
            if (f.exists()) {
                try {
                    f.writeText("0".repeat(f.length().toInt().coerceAtLeast(1)))
                } catch (e: Exception) {}
                f.delete()
            }
        }
        Logger.w("SecureVault: Destroyed")
    }
}
