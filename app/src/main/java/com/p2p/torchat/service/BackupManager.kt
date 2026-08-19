package com.p2p.torchat.service

import android.content.Context
import com.google.gson.Gson
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.crypto.MnemonicManager
import com.p2p.torchat.model.Peer
import java.util.Base64

data class AppBackupData(
    val myAlias: String,
    val isDarkTheme: Boolean,
    val isAvailable: Boolean,
    val accountExpiryDate: Long,
    val passwordHash: String?,
    val publicKey: String?,
    val privateKeyEnc: String?,
    val onionAddress: String?,
    val peers: List<Peer>
)

class BackupManager(private val context: Context) {
    private val gson = Gson()

    fun createEncryptedBackupJson(seed: List<String>, salt: ByteArray): String {
        val prefs = context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)
        val backupData = AppBackupData(
            myAlias = prefs.getString("my_alias", "Amico") ?: "Amico",
            isDarkTheme = prefs.getBoolean("is_dark_theme", true),
            isAvailable = prefs.getBoolean("is_available", false),
            accountExpiryDate = prefs.getLong("account_expiry_date", 0L),
            passwordHash = prefs.getString("app_password_hash", null),
            publicKey = prefs.getString("my_public_key", null),
            privateKeyEnc = prefs.getString("my_private_key_enc", null),
            onionAddress = prefs.getString("saved_onion_address", null),
            peers = loadPeersFromPrefs()
        )

        val json = gson.toJson(backupData)
        val encryptionKey = MnemonicManager.deriveKeyFromMnemonic(seed, salt)
        return E2EManager.encrypt(json, encryptionKey)
    }

    fun restoreFromEncryptedBackup(encryptedPkg: String, seed: List<String>): Boolean {
        return try {
            val salt = getSalt()
            val encryptionKey = MnemonicManager.deriveKeyFromMnemonic(seed, salt)
            val json = E2EManager.decrypt(encryptedPkg, encryptionKey)
            val data = gson.fromJson(json, AppBackupData::class.java)

            context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().apply {
                putString("my_alias", data.myAlias)
                putBoolean("is_dark_theme", data.isDarkTheme)
                putBoolean("is_available", data.isAvailable)
                putLong("account_expiry_date", data.accountExpiryDate)
                putString("app_password_hash", data.passwordHash)
                putString("my_public_key", data.publicKey)
                putString("my_private_key_enc", data.privateKeyEnc)
                putString("saved_onion_address", data.onionAddress)
                putString("saved_peers", serializePeers(data.peers))
                apply()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun getSalt(): ByteArray {
        val p = context.getSharedPreferences("secure_prefs_salt", Context.MODE_PRIVATE)
        val sEnc = p.getString("install_salt_enc", null) ?: return ByteArray(16)
        return Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(sEnc))
    }

    private fun loadPeersFromPrefs(): List<Peer> {
        val p = context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)
        val d = p.getString("saved_peers", null) ?: return emptyList()
        return try {
            val h = p.getString("app_password_hash", null)
            val json = if (h != null) E2EManager.decrypt(d, E2EManager.deriveKeyFromSecret(h)) else d
            gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<Peer>>() {}.type)
        } catch (e: Exception) { emptyList() }
    }

    private fun serializePeers(peers: List<Peer>): String {
        val json = gson.toJson(peers)
        val h = context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).getString("app_password_hash", null)
        return if (h != null) E2EManager.encrypt(json, E2EManager.deriveKeyFromSecret(h)) else json
    }
}
