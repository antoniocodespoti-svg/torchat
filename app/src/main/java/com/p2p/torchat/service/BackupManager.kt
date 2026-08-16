package com.p2p.torchat.service

import android.content.Context
import com.google.gson.Gson
import com.p2p.torchat.model.Peer

data class AppBackupData(
    val myAlias: String,
    val isDarkTheme: Boolean,
    val isAvailable: Boolean,
    val passwordHash: String?,
    val publicKey: String?,
    val privateKey: String?,
    val onionAddress: String?,
    val peers: List<Peer>,
)

class BackupManager(private val context: Context) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)

    fun createEncryptedBackupJson(
        mnemonic: List<String>,
        salt: ByteArray,
    ): String {
        val key = com.p2p.torchat.crypto.MnemonicManager.deriveKeyFromMnemonic(mnemonic, salt)
        val encryptedData = createEncryptedBackupJsonWithKey(key)
        // Bundle Salt with data: "SALT_BASE64|ENCRYPTED_DATA"
        val saltBase64 = java.util.Base64.getEncoder().encodeToString(salt)
        return "$saltBase64|$encryptedData"
    }

    fun createEncryptedBackupJsonWithKey(key: javax.crypto.spec.SecretKeySpec): String {
        val data =
            AppBackupData(
                myAlias = prefs.getString("my_alias", "Amico") ?: "Amico",
                isDarkTheme = prefs.getBoolean("is_dark_theme", true),
                isAvailable = prefs.getBoolean("is_available", true),
                passwordHash = prefs.getString("app_password_hash", null),
                publicKey = prefs.getString("my_public_key", null),
                privateKey = prefs.getString("my_private_key", null),
                onionAddress = prefs.getString("saved_onion_address", null),
                peers = loadPeers(),
            )
        val json = gson.toJson(data)
        return com.p2p.torchat.crypto.E2EManager.encrypt(json, key)
    }

    fun restoreFromEncryptedBackup(
        fullBackupPackage: String,
        mnemonic: List<String>,
    ): Boolean {
        return try {
            val parts = fullBackupPackage.split("|")
            val (salt, encryptedData) =
                if (parts.size == 2) {
                    // New format: Salt included
                    java.util.Base64.getDecoder().decode(parts[0]) to parts[1]
                } else {
                    // Old format fallback: Try using a fixed/current salt (unreliable after wipe)
                    null to fullBackupPackage
                }

            if (salt == null) return false // Cannot restore old format without the original salt

            val key = com.p2p.torchat.crypto.MnemonicManager.deriveKeyFromMnemonic(mnemonic, salt)
            val json = com.p2p.torchat.crypto.E2EManager.decrypt(encryptedData, key)
            val data = gson.fromJson(json, AppBackupData::class.java)

            val currentPasswordHash = prefs.getString("app_password_hash", null)

            prefs.edit().apply {
                putString("my_alias", data.myAlias)
                putBoolean("is_dark_theme", data.isDarkTheme)
                putBoolean("is_available", data.isAvailable)

                // Keep current password if it exists, otherwise take from backup
                if (currentPasswordHash != null) {
                    putString("app_password_hash", currentPasswordHash)
                } else {
                    putString("app_password_hash", data.passwordHash)
                }

                putString("my_public_key", data.publicKey)
                putString("my_private_key", data.privateKey)
                putString("saved_onion_address", data.onionAddress)
                putString("saved_peers", gson.toJson(data.peers))

                // Also store a persistent backup key derived from the mnemonic used to restore
                val keyBase64 = android.util.Base64.encodeToString(key.encoded, android.util.Base64.DEFAULT)
                putString("persistent_backup_key", keyBase64)

                remove("admin_private_key") // Ensure any old admin key is removed
                apply()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun loadPeers(): List<Peer> {
        val json = prefs.getString("saved_peers", null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<Peer>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
