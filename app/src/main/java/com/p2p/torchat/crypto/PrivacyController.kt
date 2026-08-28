package com.p2p.torchat.crypto

import android.content.Context
import android.util.Base64
import com.p2p.torchat.service.TorManager
import com.p2p.torchat.service.LocalServer
import com.p2p.torchat.util.Constants
import com.p2p.torchat.util.Logger
import com.p2p.torchat.model.Peer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import kotlin.system.exitProcess

/**
 * Enhanced Security States (v9)
 */
enum class SecurityState {
    LOCKED,
    UNLOCKING,
    UNLOCKED,
    LOCKING
}

sealed class SecurityEvent {
    object WipeRequested : SecurityEvent()
}

/**
 * Central Privacy Controller - Atomic Security State Machine.
 * Resolves Audit Point 3, 4, 6, 7, 13, 19, 20 (Atomic lifecycle, Panic Wipe, Secure Vault, Migration).
 */
object PrivacyController {
    private val mutex = Mutex()
    private val _securityState = MutableStateFlow(SecurityState.LOCKED)
    val securityState: StateFlow<SecurityState> = _securityState

    private val _securityEvents = MutableSharedFlow<SecurityEvent>(extraBufferCapacity = 1)
    val securityEvents: SharedFlow<SecurityEvent> = _securityEvents

    private var torManager: TorManager? = null
    private var localServer: LocalServer? = null
    private var context: Context? = null
    private var isInitialized = false

    private var identityContext: IdentityContext? = null
    private var vaultKey: SecretKey? = null
    private val _vaultData = MutableStateFlow<VaultData?>(null)
    val vaultData: StateFlow<VaultData?> = _vaultData

    fun initialize(ctx: Context, tor: TorManager, server: LocalServer) {
        check(!isInitialized) { "PrivacyController already initialized" }
        this.context = ctx.applicationContext
        this.torManager = tor
        this.localServer = server
        isInitialized = true
    }

    /**
     * Transitions the app to UNLOCKED state atomically.
     */
    suspend fun unlock(password: CharArray) = unlockInternal(password, null)

    /**
     * Handles initial setup or recovery.
     */
    suspend fun setup(password: CharArray, entropy: ByteArray) = unlockInternal(password, entropy)

    private suspend fun unlockInternal(password: CharArray, newEntropy: ByteArray?) = mutex.withLock {
        if (_securityState.value != SecurityState.LOCKED) return@withLock

        _securityState.value = SecurityState.UNLOCKING
        Logger.i("PrivacyController: Unlocking...")

        try {
            val ctx = context ?: throw IllegalStateException("Not initialized")
            val salt = getOrCreateSalt(ctx)

            // Updated in v9: Use Argon2id for vault protection (Audit Point 6)
            val currentVaultKey = E2EManager.deriveVaultKey(password, salt)

            var loaded = SecureVault.load(ctx, currentVaultKey)
            var entropy: ByteArray? = loaded?.second
            var data: VaultData? = loaded?.first

            if (newEntropy != null) {
                // Initial Setup / Recovery flow
                entropy = newEntropy
                data = VaultData(isTermsAccepted = true)
                SecureVault.save(ctx, data, entropy, currentVaultKey)
                Logger.i("Initial setup complete")
            }

            // Migration path
            if (data == null && newEntropy == null) {
                val migrated = tryMigrateFromV6(ctx, password, salt)
                if (migrated != null) {
                    data = migrated.first
                    entropy = migrated.second
                    SecureVault.save(ctx, data, entropy, currentVaultKey)
                    wipeV6SharedPreferences(ctx)
                    Logger.i("Migration successful")
                }
            }

            if (data == null || entropy == null) {
                // Decryption failure is the implicit proof of wrong password (Audit Point 16)
                throw SecurityException("Authentication failed")
            }

            // Derive Identity
            val identitySeed = HKDF.deriveKey(entropy, null, "TorChat/V2/IdentitySeed".toByteArray(), 32)
            val identityKeyPair = E2EManager.ed25519KeyPairFromSeed(identitySeed)

            identityContext = IdentityContext(entropy, identityKeyPair)
            vaultKey = currentVaultKey
            _vaultData.value = data

            // Restore sessions from vault (Audit Point 2: Persistence)
            SessionManager.restoreSessions(data.sessionStates)

            // Start Services
            localServer?.startServer()
            data.myOnion?.let { torManager?.setTorRunning(it) }

            _securityState.value = SecurityState.UNLOCKED
            Logger.i("System UNLOCKED")
        } catch (e: Exception) {
            Logger.e("Unlock failed") // Sanitize log
            identityContext?.wipe()
            identityContext = null
            vaultKey = null
            _vaultData.value = null
            _securityState.value = SecurityState.LOCKED
            throw e
        }
    }

    private fun tryMigrateFromV6(ctx: Context, password: CharArray, salt: ByteArray): Pair<VaultData, ByteArray>? {
        val p = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val encSeed = p.getString(Constants.KEY_SAVED_SEED_ENC, null) ?: return null

        return try {
            // v6 used HKDF directly on password
            val derived = HKDF.deriveKey(password.toUtf8ByteArray_v6(), salt, "TorChat/v2/storage/mnemonic".toByteArray(), 32)
            val key = javax.crypto.spec.SecretKeySpec(derived, "AES")

            val mnemonicWords = E2EManager.decrypt(encSeed, key).split(" ")
            val entropy = MnemonicManager.mnemonicToEntropy(mnemonicWords) ?: return null

            // Wipe derived key material
            derived.fill(0)

            val peersJson = p.getString(Constants.KEY_SAVED_PEERS, null)
            val peers = if (peersJson != null) {
                 val hash = p.getString(Constants.KEY_PASS_HASH, "") ?: ""
                 try {
                     val pk = E2EManager.deriveKeyFromSecret(hash, salt)
                     val dec = E2EManager.decrypt(peersJson, pk)
                     com.google.gson.Gson().fromJson(dec, object : com.google.gson.reflect.TypeToken<List<Peer>>() {}.type)
                 } catch (e: Exception) { emptyList<Peer>() }
            } else emptyList()

            val data = VaultData(
                myOnion = p.getString(Constants.KEY_ONION, null),
                myPublicKey = p.getString(Constants.KEY_PUBLIC_KEY, null),
                myAlias = p.getString(Constants.KEY_MY_ALIAS, "Amico") ?: "Amico",
                isDarkTheme = p.getBoolean(Constants.KEY_DARK_THEME, true),
                isAutoBackupEnabled = p.getBoolean(Constants.KEY_AUTO_BACKUP, false),
                isAvailable = p.getBoolean("is_available", false),
                expiryDate = p.getLong(Constants.KEY_EXPIRY, 0L),
                failedAttempts = p.getInt(Constants.KEY_FAILED_ATTEMPTS, 0),
                isTermsAccepted = p.getBoolean(Constants.KEY_TERMS_ACCEPTED, false)
            )
            data to entropy
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Snapshots the current RAM state (sessions) and persists it to the vault.
     */
    suspend fun persistSessionState() = updateVault {
        it.copy(sessionStates = SessionManager.getAllSessionsState())
    }

    private fun CharArray.toUtf8ByteArray_v6(): ByteArray {
        val charBuffer = java.nio.CharBuffer.wrap(this)
        val byteBuffer = java.nio.charset.StandardCharsets.UTF_8.encode(charBuffer)
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        return bytes
    }

    private fun wipeV6SharedPreferences(ctx: Context) {
        ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    suspend fun updateVault(update: (VaultData) -> VaultData) = mutex.withLock {
        val ctx = context ?: return@withLock
        val key = vaultKey ?: return@withLock
        val current = _vaultData.value ?: return@withLock
        val entropy = identityContext?.entropy ?: return@withLock

        val newData = update(current)
        SecureVault.save(ctx, newData, entropy, key)
        _vaultData.value = newData
    }

    suspend fun changePassword(oldPass: CharArray, newPass: CharArray) = mutex.withLock {
        val ctx = context ?: throw IllegalStateException("Not initialized")
        val current = _vaultData.value ?: throw IllegalStateException("Locked")
        val entropy = identityContext?.entropy ?: throw IllegalStateException("Locked")
        val salt = getOrCreateSalt(ctx)

        // Resolve Audit Point 18 (Use oldPass to verify before change)
        val oldKey = E2EManager.deriveVaultKey(oldPass, salt)
        val verification = SecureVault.load(ctx, oldKey)
        if (verification == null) throw SecurityException("Invalid old password")

        val newVaultKey = E2EManager.deriveVaultKey(newPass, salt)
        SecureVault.save(ctx, current, entropy, newVaultKey)

        vaultKey = newVaultKey
        Logger.i("Password changed successfully")
    }

    suspend fun lock() = mutex.withLock {
        if (_securityState.value == SecurityState.LOCKED || _securityState.value == SecurityState.LOCKING) return@withLock

        _securityState.value = SecurityState.LOCKING
        Logger.i("PrivacyController: Locking...")

        _securityEvents.emit(SecurityEvent.WipeRequested)

        identityContext?.wipe()
        identityContext = null
        vaultKey = null
        _vaultData.value = null
        SessionManager.lock()

        torManager?.stopTor()
        localServer?.stopServer()

        _securityState.value = SecurityState.LOCKED
        Logger.i("System LOCKED")
    }

    suspend fun panicWipe() = mutex.withLock {
        _securityState.value = SecurityState.LOCKING
        Logger.w("!!! PANIC WIPE !!!")

        _securityEvents.emit(SecurityEvent.WipeRequested)

        identityContext?.wipe()
        identityContext = null
        vaultKey = null
        _vaultData.value = null
        SessionManager.lock()

        torManager?.stopTor()
        localServer?.stopServer()

        val ctx = context
        if (ctx != null) {
            E2EManager.deleteMasterKey()
            SecureVault.destroy(ctx)

            val prefsList = listOf(Constants.PREFS_NAME, "secure_prefs_salt", "pairing_prefs")
            prefsList.forEach { name ->
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
            val torDir = File(ctx.filesDir, "tor")
            if (torDir.exists()) torDir.deleteRecursively()
        }

        _securityState.value = SecurityState.LOCKED
        Logger.w("Panic Wipe complete")
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    private fun getOrCreateSalt(ctx: Context): ByteArray {
        val p = ctx.getSharedPreferences("secure_prefs_salt", Context.MODE_PRIVATE)
        val sEncB64 = p.getString("install_salt_enc", null) ?: return generateAndSaveSalt(p)
        return try {
            val combined = Base64.getDecoder().decode(sEncB64)
            E2EManager.decryptWithHardwareKey(combined).getOrThrow()
        } catch (e: Exception) {
            throw IllegalStateException("Secure storage inaccessible")
        }
    }

    private fun generateAndSaveSalt(p: android.content.SharedPreferences): ByteArray {
        val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val enc = E2EManager.encryptWithHardwareKey(s).getOrNull() ?: byteArrayOf()
        p.edit().putString("install_salt_enc", Base64.getEncoder().encodeToString(enc)).apply()
        return s
    }

    fun getIdentityContext(): IdentityContext? = identityContext
    fun isLocked(): Boolean = _securityState.value == SecurityState.LOCKED
    fun isUnlocked(): Boolean = _securityState.value == SecurityState.UNLOCKED
}
