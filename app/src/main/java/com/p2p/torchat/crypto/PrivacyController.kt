package com.p2p.torchat.crypto

import android.content.Context
import android.util.Log
import com.p2p.torchat.service.TorManager
import com.p2p.torchat.service.LocalServer
import com.p2p.torchat.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.KeyPair

enum class SecurityState {
    LOCKED,
    UNLOCKED
}

/**
 * Central Privacy Controller to manage application security boundaries.
 * Coordinates session cleanup, identity wiping, and service termination.
 */
object PrivacyController {
    private val _securityState = MutableStateFlow(SecurityState.LOCKED)
    val securityState: StateFlow<SecurityState> = _securityState

    private var torManager: TorManager? = null
    private var localServer: LocalServer? = null
    private var onWipeMessages: (() -> Unit)? = null
    private var onWipeIdentity: (() -> Unit)? = null

    fun initialize(
        tor: TorManager,
        server: LocalServer,
        wipeMessages: () -> Unit,
        wipeIdentity: () -> Unit
    ) {
        this.torManager = tor
        this.localServer = server
        this.onWipeMessages = wipeMessages
        this.onWipeIdentity = wipeIdentity
    }

    /**
     * Transitions the app to a LOCKED state, wiping RAM-sensitive data and stopping services.
     */
    suspend fun lock() {
        Log.i(Constants.TAG, "PrivacyController: Locking system...")

        // 1. Wipe sessions from RAM
        SessionManager.lock()

        // 2. Clear UI message cache
        onWipeMessages?.invoke()

        // 3. Nullify identity key reference
        onWipeIdentity?.invoke()

        // 4. Stop networking services
        torManager?.stopTor()
        localServer?.stopServer()

        _securityState.value = SecurityState.LOCKED
    }

    fun unlock() {
        _securityState.value = SecurityState.UNLOCKED
    }

    fun isLocked(): Boolean = _securityState.value == SecurityState.LOCKED
}
