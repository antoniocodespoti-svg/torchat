package com.p2p.torchat.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class TorState {
    object Stopped : TorState()

    object Starting : TorState()

    data class Running(val onionAddress: String, val socksPort: Int = 9050) : TorState()

    data class Error(val message: String) : TorState()
}

class TorManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)
    private val _torState = MutableStateFlow<TorState>(TorState.Stopped)
    val torState: StateFlow<TorState> = _torState

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        const val ACTION_REQUEST_V3_ONION_SERVICE = "org.torproject.android.intent.action.REQUEST_V3_ONION_SERVICE"
    }

    /**
     * Checks if Orbot is installed on the device.
     */
    fun isOrbotInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Creates the Intent to request a real v3 Onion Service from Orbot.
     * Resolves Audit Points 1 and 2.
     */
    fun getOrbotRequestIntent(localPort: Int = 8080): Intent {
        return Intent(ACTION_REQUEST_V3_ONION_SERVICE).apply {
            setPackage(ORBOT_PACKAGE)
            putExtra("localPort", localPort)
            putExtra("onionPort", 80)
            putExtra("name", "TorP2PChat Secure Node")
        }
    }

    fun setTorRunning(onionAddress: String) {
        prefs.edit().putString("saved_onion_address", onionAddress).apply()
        _torState.value = TorState.Running(onionAddress)
    }

    fun setTorError(message: String) {
        _torState.value = TorState.Error(message)
    }

    fun stopTorService() {
        _torState.value = TorState.Stopped
    }
}
