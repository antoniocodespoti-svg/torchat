package com.p2p.supermaster.service

import android.content.Context
import com.google.gson.Gson
import com.p2p.supermaster.MasterCollaborator
import com.p2p.supermaster.crypto.E2EManager
import com.p2p.supermaster.crypto.MnemonicManager

data class SuperBackupData(
    val collaborators: List<MasterCollaborator>,
    val seed: List<String>,
    val passwordHash: String?,
)

class SuperBackupManager(private val context: Context) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE)

    fun createEncryptedBackup(
        mnemonic: List<String>,
        salt: ByteArray,
        collaborators: List<MasterCollaborator>,
        passwordHash: String?,
    ): String {
        val key = MnemonicManager.deriveKeyFromMnemonic(mnemonic, salt)
        val encryptedData = createEncryptedBackupWithKey(key, collaborators, passwordHash)
        // Bundle Salt with data: "SALT_BASE64|ENCRYPTED_DATA"
        val saltBase64 = java.util.Base64.getEncoder().encodeToString(salt)
        return "$saltBase64|$encryptedData"
    }

    fun createEncryptedBackupWithKey(
        key: javax.crypto.spec.SecretKeySpec,
        collaborators: List<MasterCollaborator>,
        passwordHash: String?,
    ): String {
        val data = SuperBackupData(collaborators, emptyList(), passwordHash)
        val json = gson.toJson(data)
        return E2EManager.encrypt(json, key)
    }

    fun restoreFromEncryptedBackup(
        fullBackupPackage: String,
        mnemonic: List<String>,
    ): SuperBackupData? {
        return try {
            val parts = fullBackupPackage.split("|")
            val (salt, encryptedData) =
                if (parts.size == 2) {
                    // New format: Salt included
                    java.util.Base64.getDecoder().decode(parts[0]) to parts[1]
                } else {
                    // Old format fallback
                    null to fullBackupPackage
                }

            if (salt == null) return null

            val key = MnemonicManager.deriveKeyFromMnemonic(mnemonic, salt)
            val json = E2EManager.decrypt(encryptedData, key)
            val data = gson.fromJson(json, SuperBackupData::class.java)

            val currentPasswordHash = prefs.getString("super_password_hash", null)

            // Store persistent key during restoration
            val keyBase64 = android.util.Base64.encodeToString(key.encoded, android.util.Base64.DEFAULT)
            prefs.edit().putString("persistent_backup_key", keyBase64).apply()

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
