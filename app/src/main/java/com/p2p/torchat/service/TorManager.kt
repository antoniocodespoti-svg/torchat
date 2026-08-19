package com.p2p.torchat.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

sealed class TorState {
    object Stopped : TorState()
    object Starting : TorState()
    data class Running(val onionAddress: String, val socksPort: Int = 9050) : TorState()
    data class Error(val message: String) : TorState()
}

/**
 * Enhanced TorManager with Lifecycle Management.
 * Resolves Audit Point TOR-001 (Critical).
 */
class TorManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)
    private val _torState = MutableStateFlow<TorState>(TorState.Stopped)
    val torState: StateFlow<TorState> = _torState

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        const val ACTION_REQUEST_V3_ONION_SERVICE = "org.torproject.android.intent.action.REQUEST_V3_ONION_SERVICE"
    }

    fun isOrbotInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
        true
    } catch (e: Exception) { false }

    fun getOrbotRequestIntent(): Intent = Intent(ACTION_REQUEST_V3_ONION_SERVICE).apply {
        setPackage(ORBOT_PACKAGE)
        putExtra("localPort", 8080)
        putExtra("onionPort", 80)
        putExtra("name", "TorP2PChat")
    }

    /**
     * Actively verifies the status of the Tor SOCKS proxy.
     */
    fun checkTorHealth(onionAddress: String) {
        _torState.value = TorState.Starting
        val socksPort = 9050

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Short timeout to check if SOCKS proxy is alive
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", socksPort), 5000)
                socket.close()

                _torState.value = TorState.Running(onionAddress, socksPort)
                prefs.edit().putString("saved_onion_address", onionAddress).apply()
            } catch (e: Exception) {
                _torState.value = TorState.Error("Tor SOCKS Proxy not reachable. Please open Orbot.")
            }
        }
    }

    fun setTorRunning(onionAddress: String) {
        checkTorHealth(onionAddress)
    }
}
