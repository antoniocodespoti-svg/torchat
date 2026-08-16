package com.p2p.supermaster.service

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
    private val prefs = context.getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE)
    private val _torState = MutableStateFlow<TorState>(TorState.Stopped)
    val torState: StateFlow<TorState> = _torState

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        const val ACTION_REQUEST_V3_ONION_SERVICE = "org.torproject.android.intent.action.REQUEST_V3_ONION_SERVICE"
    }

    fun isOrbotInstalled(): Boolean =
        try {
            context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }

    fun getOrbotRequestIntent(): Intent =
        Intent(ACTION_REQUEST_V3_ONION_SERVICE).apply {
            setPackage(ORBOT_PACKAGE)
            putExtra("localPort", 8082)
            putExtra("onionPort", 80)
            putExtra("name", "TorSuperMaster Node")
        }

    fun startTorService() { }

    fun setTorRunning(o: String) {
        prefs.edit().putString("saved_onion_address", o).apply()
        _torState.value = TorState.Running(o)
    }

    fun setTorError(m: String) {
        _torState.value = TorState.Error(m)
    }
}
