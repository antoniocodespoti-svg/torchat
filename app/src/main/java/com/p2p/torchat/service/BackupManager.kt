package com.p2p.torchat.service

import android.content.Context
import com.google.gson.Gson
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.crypto.MnemonicManager
import com.p2p.torchat.model.Peer
import java.security.SecureRandom
import java.util.Base64

/**
 * Portable Backup Format.
 * Resolves Audit Point 7 & 8 (Salt & Seed Portability).
 */
data class PortableBackup(
    val format: String = "torchat-backup",
    val version: Int = 1,
    /** Base64 encoded salt used for KDF */
    val salt: String,
    val encryptedPayload: String,
)

data class BackupPayload(
    val myAlias: String,
    /** Root entropy for identity recovery */
    val entropy: String,
    /** Public key to verify restore integrity (Audit 11) */
    val identityPublicKey: String,
    val accountExpiryDate: Long,
    val peers: List<Peer>,
)

class BackupManager(private val context: Context) {
    private val gson = Gson()

    fun createEncryptedBackupJson(
        seed: List<String>,
        salt: ByteArray,
    ): String {
        val prefs = context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)
        val entropy = MnemonicManager.mnemonicToEntropy(seed) ?: throw Exception("Invalid seed")

        // Derive PK to include in backup for integrity check
        val idSeed = com.p2p.torchat.crypto.HKDF.deriveKey(entropy, null, "TorChat/V2/IdentitySeed".toByteArray(), 32)
        val idKeyPair = E2EManager.ed25519KeyPairFromSeed(idSeed)
        val idPK = E2EManager.publicKeyToString(idKeyPair.public)

        val payload =
            BackupPayload(
                myAlias = prefs.getString("my_alias", "Amico") ?: "Amico",
                entropy = Base64.getEncoder().encodeToString(entropy),
                identityPublicKey = idPK,
                accountExpiryDate = prefs.getLong("account_expiry_date", 0L),
                peers = loadPeersFromPrefs(),
            )

        val jsonPayload = gson.toJson(payload)
        val encryptionKey = E2EManager.deriveKeyFromPassword(seed.joinToString(" "), salt)
        val encrypted = E2EManager.encrypt(jsonPayload, encryptionKey)

        val portable =
            PortableBackup(
                salt = Base64.getEncoder().encodeToString(salt),
                encryptedPayload = encrypted,
            )
        return gson.toJson(portable)
    }

    fun restoreFromEncryptedBackup(
        backupJson: String,
        seed: List<String>,
    ): Boolean {
        return try {
            val portable = gson.fromJson(backupJson, PortableBackup::class.java)
            val salt = Base64.getDecoder().decode(portable.salt)
            val encryptionKey = E2EManager.deriveKeyFromPassword(seed.joinToString(" "), salt)

            val jsonPayload = E2EManager.decrypt(portable.encryptedPayload, encryptionKey)
            val data = gson.fromJson(jsonPayload, BackupPayload::class.java)

            // Verification of restore integrity (Audit Point 11)
            val idSeed = com.p2p.torchat.crypto.HKDF.deriveKey(MnemonicManager.mnemonicToEntropy(seed)!!, null, "TorChat/V2/IdentitySeed".toByteArray(), 32)
            val idKeyPair = E2EManager.ed25519KeyPairFromSeed(idSeed)
            val derivedPK = E2EManager.publicKeyToString(idKeyPair.public)

            if (derivedPK != data.identityPublicKey) {
                return false // Mismatch detected
            }

            context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().apply {
                putString("my_alias", data.myAlias)
                putLong("account_expiry_date", data.accountExpiryDate)
                putString("saved_peers", serializePeers(data.peers))
                apply()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadPeersFromPrefs(): List<Peer> {
        val p = context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)
        val d = p.getString("saved_peers", null) ?: return emptyList()
        return try {
            val h = p.getString("app_password_hash", null)
            val json = if (h != null) E2EManager.decrypt(d, E2EManager.deriveKeyFromSecret(h, getInstallSalt())) else d
            gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<Peer>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializePeers(peers: List<Peer>): String {
        val json = gson.toJson(peers)
        val h = context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).getString("app_password_hash", null)
        return if (h != null) E2EManager.encrypt(json, E2EManager.deriveKeyFromSecret(h, getInstallSalt())) else json
    }

    private fun getInstallSalt(): ByteArray {
        val p = context.getSharedPreferences("secure_prefs_salt", Context.MODE_PRIVATE)
        val sEnc = p.getString("install_salt_enc", null) ?: return generateAndSaveSalt(p)
        return try {
            Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(sEnc).getOrThrow())
        } catch (e: Exception) {
            // Resolves Audit Point 11: Decrypt failure -> HARD ERROR, not silent regeneration.
            throw IllegalStateException("Failed to decrypt storage salt. Local data may be inaccessible.", e)
        }
    }

    private fun generateAndSaveSalt(p: android.content.SharedPreferences): ByteArray {
        val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val enc = E2EManager.encryptWithHardwareKey(Base64.getEncoder().encodeToString(s)).getOrNull() ?: ""
        p.edit().putString("install_salt_enc", enc).apply()
        return s
    }
}
