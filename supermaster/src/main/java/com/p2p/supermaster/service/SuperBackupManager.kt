package com.p2p.supermaster.service

import android.content.Context
import com.google.gson.Gson
import com.p2p.supermaster.MasterCollaborator
import com.p2p.supermaster.crypto.E2EManager
import com.p2p.supermaster.crypto.MnemonicManager

data class SuperBackupData(
    val collaborators: List<MasterCollaborator>,
    val entropy: String,
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
        val entropy = MnemonicManager.mnemonicToEntropy(mnemonic) ?: throw Exception("Invalid seed")
        val data =
            SuperBackupData(
                collaborators = collaborators,
                entropy = java.util.Base64.getEncoder().encodeToString(entropy),
                passwordHash = passwordHash,
            )
        val json = gson.toJson(data)
        val encryptedData = E2EManager.encrypt(json, key)

        // Bundle Salt with data: "SALT_BASE64|ENCRYPTED_DATA"
        val saltBase64 = java.util.Base64.getEncoder().encodeToString(salt)
        return "$saltBase64|$encryptedData"
    }

    fun restoreFromEncryptedBackup(
        fullBackupPackage: String,
        mnemonic: List<String>,
    ): SuperBackupData? {
        return try {
            val parts = fullBackupPackage.split("|")
            val (salt, encryptedData) =
                if (parts.size == 2) {
                    java.util.Base64.getDecoder().decode(parts[0]) to parts[1]
                } else {
                    return null
                }

            val key = MnemonicManager.deriveKeyFromMnemonic(mnemonic, salt)
            val json = E2EManager.decrypt(encryptedData, key)
            val data = gson.fromJson(json, SuperBackupData::class.java)

            prefs.edit().apply {
                putString("super_password_hash", data.passwordHash)
                putString("super_seed", mnemonic.joinToString(" "))
                apply()
            }
            data
        } catch (e: Exception) {
            null
        }
    }
}
