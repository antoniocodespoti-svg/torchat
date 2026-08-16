package com.p2p.tormaster.service

import android.content.Context
import com.p2p.tormaster.crypto.E2EManager
import javax.crypto.spec.SecretKeySpec

class MasterWalletManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("master_wallet_prefs", Context.MODE_PRIVATE)

    private fun getWalletKey(): SecretKeySpec {
        val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        // We use a fixed derivation key for the wallet
        return E2EManager.deriveKeyFromSecret("MASTER_WALLET_GHOST_2026_$deviceId")
    }

    /**
     * Gets total days available.
     * Uses encryption to prevent manual editing of the prefs file.
     */
    fun getBalance(): Int {
        val encrypted = prefs.getString("balance_enc", null) ?: return 0
        return try {
            val decrypted = E2EManager.decrypt(encrypted, getWalletKey())
            decrypted.toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Sells days to a client. Returns true if enough balance.
     */
    fun spendDays(days: Int): Boolean {
        val current = getBalance()
        if (current >= days) {
            saveBalance(current - days)
            return true
        }
        return false
    }

    /**
     * Recharges the Master wallet.
     */
    fun addDays(days: Int) {
        saveBalance(getBalance() + days)
    }

    private fun saveBalance(balance: Int) {
        val encrypted = E2EManager.encrypt(balance.toString(), getWalletKey())
        prefs.edit().putString("balance_enc", encrypted).apply()
    }
}
