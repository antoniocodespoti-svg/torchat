package com.p2p.tormaster.service

import android.content.Context
import com.google.gson.Gson
import com.p2p.tormaster.crypto.E2EManager
import com.p2p.tormaster.crypto.MnemonicManager

data class MasterBackupData(
    val passwordHash: String?,
    val walletBalance: Int,
    val entropy: String,
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
        val entropy = MnemonicManager.mnemonicToEntropy(mnemonic) ?: throw Exception("Invalid seed")
        val data =
            MasterBackupData(
                passwordHash = prefs.getString("master_password_hash", null),
                walletBalance = balance,
                entropy = java.util.Base64.getEncoder().encodeToString(entropy),
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
    ): MasterBackupData? {
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
            val data = gson.fromJson(json, MasterBackupData::class.java)

            prefs.edit().apply {
                putString("master_password_hash", data.passwordHash)
                putString("master_seed", mnemonic.joinToString(" "))
                // Note: Balance is handled by the wallet manager or caller
                apply()
            }

            data
        } catch (e: Exception) {
            null
        }
    }
}
