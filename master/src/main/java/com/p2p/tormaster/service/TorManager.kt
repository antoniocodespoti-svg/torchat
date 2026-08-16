package com.p2p.tormaster.service

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
    private val prefs = context.getSharedPreferences("master_prefs", Context.MODE_PRIVATE)
    private val _torState = MutableStateFlow<TorState>(TorState.Stopped)
    val torState: StateFlow<TorState> = _torState

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        const val ACTION_REQUEST_V3_ONION_SERVICE = "org.torproject.android.intent.action.REQUEST_V3_ONION_SERVICE"
    }

    fun isOrbotInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getOrbotRequestIntent(): Intent {
        return Intent(ACTION_REQUEST_V3_ONION_SERVICE).apply {
            setPackage(ORBOT_PACKAGE)
            putExtra("localPort", 8081) // Master uses different port
            putExtra("onionPort", 80)
            putExtra("name", "TorMaster Wallet Node")
        }
    }

    fun startTorService() { /* Legacy trigger, logic now handled by Orbot intent */ }

    fun setTorRunning(onionAddress: String) {
        prefs.edit().putString("saved_onion_address", onionAddress).apply()
        _torState.value = TorState.Running(onionAddress)
    }

    fun setTorError(message: String) {
        _torState.value = TorState.Error(message)
    }
}
