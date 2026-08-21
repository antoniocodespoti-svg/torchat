package com.p2p.torchat.service

import android.content.Context
import android.content.Intent
import com.p2p.torchat.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

sealed class TorState {
    object Stopped : TorState()
    object Starting : TorState()
    data class Running(val onionAddress: String, val socksPort: Int = Constants.TOR_SOCKS_PORT) : TorState()
    data class Error(val message: String) : TorState()
}

/**
 * Enhanced TorManager with Lifecycle Management.
 * Resolves Audit Point TOR-001 (Critical).
 */
class TorManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
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
        putExtra("localPort", Constants.LOCAL_SERVER_PORT)
        putExtra("onionPort", 80)
        putExtra("name", "TorP2PChat")
    }

    /**
     * Verifies the status of the Tor SOCKS proxy via real SOCKS5 handshake.
     */
    fun checkTorHealth(onionAddress: String) {
        _torState.value = TorState.Starting

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", Constants.TOR_SOCKS_PORT), 5000)

                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                // SOCKS5 Greeting: Version 5, 1 Method, No Auth (0x00)
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()

                val version = input.readByte()
                val method = input.readByte()

                socket.close()

                if (version == 0x05.toByte()) {
                    _torState.value = TorState.Running(onionAddress, Constants.TOR_SOCKS_PORT)
                    prefs.edit().putString(Constants.KEY_ONION, onionAddress).apply()
                } else {
                    _torState.value = TorState.Error("Invalid SOCKS version: $version")
                }
            } catch (e: Exception) {
                _torState.value = TorState.Error("Tor SOCKS Proxy not reachable. Please start Orbot.")
            }
        }
    }

    fun setTorRunning(onionAddress: String) {
        checkTorHealth(onionAddress)
    }
}
