package com.p2p.tormaster.service

import android.content.Context
import com.google.gson.Gson
import com.p2p.tormaster.crypto.E2EManager
import com.p2p.tormaster.crypto.MnemonicManager

data class MasterBackupData(
    val passwordHash: String?,
    val walletBalance: Int,
)

class MasterBackupManager(private val context: Context) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("master_prefs", Context.MODE_PRIVATE)

    fun createEncryptedBackupJson(
        mnemonic: List<String>,
        salt: ByteArray,
        balance: Int,
    ): String {
        val key = MnemonicManager.deriveKeyFromMnemonic(mnemonic, salt)
        val encryptedData = createEncryptedBackupJsonWithKey(key, balance)
        // Bundle Salt with data: "SALT_BASE64|ENCRYPTED_DATA"
        val saltBase64 = java.util.Base64.getEncoder().encodeToString(salt)
        return "$saltBase64|$encryptedData"
    }

    fun createEncryptedBackupJsonWithKey(
        key: javax.crypto.spec.SecretKeySpec,
        balance: Int,
    ): String {
        val data =
            MasterBackupData(
                passwordHash = prefs.getString("master_password_hash", null),
                walletBalance = balance,
            )
        val json = gson.toJson(data)
        return E2EManager.encrypt(json, key)
    }

    fun restoreFromEncryptedBackup(
        fullBackupPackage: String,
        mnemonic: List<String>,
    ): MasterBackupData? {
        return try {
            val parts = fullBackupPackage.split("|")
            val (salt, encryptedData) =
                if (parts.size == 2) {
                    // New format: Salt included
                    java.util.Base64.getDecoder().decode(parts[0]) to parts[1]
                } else {
                    // Old format fallback: Not reliable after wipe
                    null to fullBackupPackage
                }

            if (salt == null) return null

            val key = MnemonicManager.deriveKeyFromMnemonic(mnemonic, salt)
            val json = E2EManager.decrypt(encryptedData, key)
            val data = gson.fromJson(json, MasterBackupData::class.java)

            val currentPasswordHash = prefs.getString("master_password_hash", null)

            // Store persistent key during restoration
            val keyBase64 = android.util.Base64.encodeToString(key.encoded, android.util.Base64.DEFAULT)
            prefs.edit().putString("persistent_backup_key", keyBase64).apply()

            // If current password exists, use it instead of the one in backup
            if (currentPasswordHash != null) {
                data.copy(passwordHash = currentPasswordHash)
            } else {
                data
            }
        } catch (e: Exception) {
            null
        }
    }
}
