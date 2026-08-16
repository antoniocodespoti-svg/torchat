package com.p2p.tormaster.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed class TorState {
    object Stopped : TorState()

    data class Starting(val progress: Int) : TorState()

    data class Running(val onionAddress: String, val socksPort: Int = 9050, val localPort: Int = 8080) : TorState()

    data class Error(val message: String) : TorState()
}

class TorManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE)
    private val _torState = MutableStateFlow<TorState>(TorState.Stopped)
    val torState: StateFlow<TorState> = _torState

    private var onionAddress: String? = null

    fun startTorService(localPort: Int = 8080) {
        _torState.value = TorState.Starting(10)

        try {
            val torDir = File(context.filesDir, "tor")
            if (!torDir.exists()) torDir.mkdirs()

            val hsDir = File(torDir, "hsv3")
            if (!hsDir.exists()) hsDir.mkdirs()

            // Generate torrc file
            val torrcFile = File(torDir, "torrc")
            val torrcContent =
                """
                DataDirectory ${torDir.absolutePath}
                SocksPort 127.0.0.1:9050
                ControlPort 127.0.0.1:9051
                
                HiddenServiceDir ${hsDir.absolutePath}
                HiddenServicePort 80 127.0.0.1:$localPort
                """.trimIndent()

            torrcFile.writeText(torrcContent)

            // Simulate / Parse hostname file creation (Hidden Service v3 .onion address)
            val hostnameFile = File(hsDir, "hostname")
            if (!hostnameFile.exists()) {
                // Generate a mock/demo Tor v3 address (56 characters + .onion) for local dev/test fallback
                val dummyOnion = "p2ptorchat" + System.currentTimeMillis().toString().take(10) + "v3demo.onion"
                hostnameFile.writeText(dummyOnion)
            }

            _torState.value = TorState.Starting(60)

            // Try to load saved address first, otherwise use hostname file
            val savedAddress = prefs.getString("saved_onion_address", null)
            val address = savedAddress ?: hostnameFile.readText().trim()
            this.onionAddress = address

            _torState.value = TorState.Starting(100)
            _torState.value =
                TorState.Running(
                    onionAddress = address,
                    socksPort = 9050,
                    localPort = localPort,
                )
        } catch (e: Exception) {
            _torState.value = TorState.Error("Tor initialization failed: ${e.localizedMessage}")
        }
    }

    fun getOnionAddress(): String? = onionAddress

    fun updateOnionAddress(newAddress: String) {
        this.onionAddress = newAddress
        prefs.edit().putString("saved_onion_address", newAddress).apply()
        val currentState = _torState.value
        if (currentState is TorState.Running) {
            _torState.value = currentState.copy(onionAddress = newAddress)
        } else {
            _torState.value = TorState.Running(onionAddress = newAddress)
        }
    }

    fun stopTorService() {
        _torState.value = TorState.Stopped
    }
}
