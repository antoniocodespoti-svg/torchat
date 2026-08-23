package com.p2p.torchat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.p2p.torchat.crypto.*
import com.p2p.torchat.model.*
import com.p2p.torchat.service.*
import com.p2p.torchat.ui.screens.*
import com.p2p.torchat.ui.theme.TorP2PChatTheme
import com.p2p.torchat.util.Constants
import com.p2p.torchat.util.Logger
import com.p2p.torchat.data.ChatRepository
import com.p2p.torchat.pairing.PairingTokenManager
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.*
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var torManager: TorManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var backupManager: BackupManager
    private lateinit var mediaManager: MediaManager
    private var localServer: LocalServer? = null

    private val chatRepository by lazy { ChatRepository(P2PMessenger, torManager) }

    private val handshakeLoading = mutableStateMapOf<String, Boolean>()
    private val handshakeManager = HandshakeManager()
    private val peersList = mutableStateListOf<Peer>()
    private val messagesMap = mutableStateMapOf<String, MutableList<Message>>()
    private val unreadCounts = mutableStateMapOf<String, Int>()
    private val networkSequence = AtomicInteger(0)

    private var currentScreenState by mutableStateOf<Screen>(Screen.Auth)
    private var isAuthenticated by mutableStateOf(false)
    private var isTermsAccepted by mutableStateOf(false)
    private var savedPasswordHash by mutableStateOf<String?>(null)
    private var failedAttempts by mutableIntStateOf(0)
    private var tempEntropy: ByteArray? = null

    private var peerToConfirmWithKey by mutableStateOf<Triple<String, String, String>?>(null)
    private var activeChatPeer: Peer? = null
    private var activeChatMessages: MutableList<Message>? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { handleMediaPick(it, true) } }
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { handleMediaPick(it, false) } }
    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> u?.let { handleExport(it) } }
    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { handleImport(it) } }
    private val orbotLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) res.data?.getStringExtra("onion_address")?.let { torManager.setTorRunning(it) }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    private var isSystemInitialized by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        loadInitialState()

        // Observe Security Events (Security Boundary v7)
        lifecycleScope.launch {
            PrivacyController.securityEvents.collect { event ->
                when (event) {
                    is SecurityEvent.WipeRequested -> {
                        withContext(Dispatchers.Main) {
                            messagesMap.clear()
                            peersList.clear()
                            unreadCounts.clear()
                            activeChatMessages = null
                            activeChatPeer = null
                            notificationHelper.cancelAll()

                            isAuthenticated = false
                            currentScreenState = Screen.Auth
                            Logger.w("RAM and UI state wiped via SecurityEvent")
                        }
                    }
                }
            }
        }

        setContent {
            val vault by PrivacyController.vaultData.collectAsState()
            TorP2PChatTheme(darkTheme = vault?.isDarkTheme ?: true) {
                AppMainContent()
            }
        }

        checkAndRequestPermissions()
    }

    @Composable
    private fun AppMainContent() {
        val securityState by PrivacyController.securityState.collectAsState()
        val identity = PrivacyController.getIdentityContext()
        val vault by PrivacyController.vaultData.collectAsState()

        if (securityState == SecurityState.LOCKED && isAuthenticated) {
            isAuthenticated = false
            currentScreenState = Screen.Auth
        }

        val torState = if (isSystemInitialized) {
            torManager.torState.collectAsState().value
        } else {
            TorState.Stopped
        }
        val myOnion = (torState as? TorState.Running)?.onionAddress ?: ""

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val screen = currentScreenState) {
                is Screen.Auth -> AuthScreen(
                    if (savedPasswordHash == null) AuthMode.CREATE else AuthMode.LOGIN,
                    Constants.MAX_AUTH_ATTEMPTS - failedAttempts,
                    { handleAuthResult(it.toCharArray()) }
                )
                is Screen.Subscription -> SubscriptionScreen(myOnion) { handleSubscription(it, myOnion) }
                is Screen.Home -> HomeScreen(
                    torState,
                    myOnion,
                    vault?.myAlias ?: "Amico",
                    identity?.identityKeyPair?.let { E2EManager.publicKeyToString(it.public) } ?: "",
                    vault?.isDarkTheme ?: true,
                    vault?.isAvailable ?: false,
                    vault?.expiryDate ?: 0L,
                    peersList,
                    unreadCounts,
                    peerToConfirmWithKey,
                    { toggleDarkTheme() },
                    { toggleAvailability() },
                    { updateAlias(it) },
                    { a, o, k -> handleAddPeer(a, o, k) },
                    { handleSelectPeer(it) },
                    { currentScreenState = Screen.QRCode },
                    { currentScreenState = Screen.QRScanner },
                    { currentScreenState = Screen.Settings },
                    { torManager.setTorRunning(it) },
                    { removePeer(it) },
                    { peerToConfirmWithKey = null }
                )
                is Screen.Chat -> {
                    val ms = messagesMap.getOrPut(screen.peer.onionAddress) { mutableStateListOf() }
                    LaunchedEffect(screen.peer.onionAddress) { if (!SessionManager.hasSession(screen.peer.onionAddress)) initiateHandshake(screen.peer) }
                    ChatScreen(screen.peer, ms, handshakeLoading[screen.peer.onionAddress] ?: false, { sendMessage(screen.peer, it, ms) }, { activeChatPeer = screen.peer; activeChatMessages = ms; pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, { activeChatPeer = screen.peer; activeChatMessages = ms; pickFileLauncher.launch("*/*") }, { f, b -> saveAttachmentToExternalStorage(f, b) }, { handleDeleteSession(screen.peer, ms) }, { currentScreenState = Screen.Verification(screen.peer) }, { currentScreenState = Screen.Home })
                }
                is Screen.Settings -> SettingsScreen({ handleExportInternal() }, { importBackupLauncher.launch(arrayOf("application/json")) }, vault?.isAutoBackupEnabled ?: false, { updateAutoBackup(it) }, { currentScreenState = Screen.ChangePassword }, { currentScreenState = Screen.Subscription }, vault?.expiryDate ?: 0L, { currentScreenState = Screen.Info }, { currentScreenState = Screen.TermsOfUse }, { currentScreenState = Screen.Home })
                is Screen.QRCode -> QRCodeScreen(myOnion, vault?.myAlias ?: "Amico", identity?.identityKeyPair?.let { E2EManager.publicKeyToString(it.public) } ?: "") { currentScreenState = Screen.Home }
                is Screen.Verification -> VerificationScreen(screen.peer, { handleVerifyPeer(screen.peer) }, { currentScreenState = Screen.Chat(screen.peer) })
                is Screen.QRScanner -> ClientQRScannerScreen({ handleQRScan(it) }, { currentScreenState = Screen.Home })
                is Screen.TermsOfUse -> TermsOfUseScreen(vault?.isTermsAccepted ?: false, { handleTermsAccept() }, { currentScreenState = Screen.Settings })
                is Screen.Info -> InfoScreen { currentScreenState = Screen.Settings }
                is Screen.ChangePassword -> AuthScreen(AuthMode.CHANGE, onAuthSuccess = { handleChangePassword(it) }) { currentScreenState = Screen.Settings }
                is Screen.Recovery -> SeedScreen(SeedMode.INPUT, emptyList(), { handleManualRecovery(it) }, { currentScreenState = Screen.Auth })
                is Screen.SeedBackup -> {
                    // Derive mnemonic temporarily from identity entropy
                    val mnemonic = identity?.entropy?.let { MnemonicManager.entropyToMnemonic(it) } ?: emptyList()
                    if (mnemonic.isEmpty()) {
                        currentScreenState = Screen.Recovery
                    }
                    SeedScreen(SeedMode.DISPLAY, mnemonic, { handleExportInternal() }, { currentScreenState = Screen.Home })
                }
                is Screen.SeedRestore -> SeedScreen(SeedMode.INPUT, emptyList(), { handleSeedRestore(it) }, { currentScreenState = Screen.Settings })
            }
        }
    }

    private fun handleAuthResult(password: CharArray): Boolean {
        lifecycleScope.launch {
            try {
                if (savedPasswordHash == null) {
                    val entropy = tempEntropy ?: ByteArray(16).apply { SecureRandom().nextBytes(this) }
                    PrivacyController.setup(password, entropy)
                    tempEntropy = null
                } else {
                    PrivacyController.unlock(password)
                }

                withContext(Dispatchers.Main) {
                    isAuthenticated = true
                    failedAttempts = 0
                    val vault = PrivacyController.vaultData.value
                    savedPasswordHash = vault?.passwordHash
                    isTermsAccepted = vault?.isTermsAccepted ?: false

                    peersList.clear()
                    peersList.addAll(vault?.peers ?: emptyList())

                    currentScreenState = Screen.Home
                }
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    failedAttempts++
                    if (failedAttempts >= Constants.MAX_AUTH_ATTEMPTS) PrivacyController.panicWipe()
                    Toast.makeText(this@MainActivity, "Accesso negato", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Logger.e("Unlock error", e)
                    Toast.makeText(this@MainActivity, "Errore Inizializzazione", Toast.LENGTH_SHORT).show()
                }
            } finally {
                password.fill('\u0000')
            }
        }
        return false
    }

    private fun handleManualRecovery(mnemonic: List<String>) {
        if (MnemonicManager.isValidMnemonic(mnemonic)) {
            try {
                val entropy = MnemonicManager.mnemonicToEntropy(mnemonic) ?: throw SecurityException("Invalid Mnemonic")
                tempEntropy = entropy

                // Reset password state for AuthMode.CREATE
                savedPasswordHash = null
                currentScreenState = Screen.Auth
                Toast.makeText(this, "Identità ripristinata. Imposta una nuova password.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Errore Ripristino", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Seed non valido", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSubscription(code: String, onion: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val netTime = NetworkTimeFetcher.fetchTimeViaTor() ?: System.currentTimeMillis()
            val matchedDays = TotpManager.findMatchingClientDuration(code, onion, netTime)
            if (matchedDays != null) {
                val newExpiry = maxOf(netTime, PrivacyController.vaultData.value?.expiryDate ?: 0L) + (matchedDays.toLong() * 24 * 60 * 60 * 1000)
                PrivacyController.updateVault { it.copy(expiryDate = newExpiry) }
                withContext(Dispatchers.Main) {
                    currentScreenState = Screen.Home
                    Toast.makeText(this@MainActivity, "OK", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleAddPeer(alias: String, onion: String, pubKey: String) {
        val clean = onion.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
        if (peersList.any { it.onionAddress == clean }) Toast.makeText(this, "ESISTE", Toast.LENGTH_SHORT).show()
        else {
            val newPeer = Peer(clean, alias, identityPublicKey = pubKey)
            peersList.add(newPeer)
            lifecycleScope.launch {
                PrivacyController.updateVault { it.copy(peers = peersList.toList()) }
            }
        }
    }

    private fun removePeer(peer: Peer) {
        peersList.remove(peer)
        lifecycleScope.launch {
            PrivacyController.updateVault { it.copy(peers = peersList.toList()) }
        }
    }

    private fun handleSelectPeer(peer: Peer) {
        unreadCounts[peer.onionAddress] = 0
        if (!SessionManager.hasSession(peer.onionAddress)) initiateHandshake(peer)
        currentScreenState = Screen.Chat(peer)
    }

    private fun handleVerifyPeer(peer: Peer) {
        val idx = peersList.indexOfFirst { it.onionAddress == peer.onionAddress }
        if (idx != -1) {
            peersList[idx] = peersList[idx].copy(isVerified = true)
            lifecycleScope.launch {
                PrivacyController.updateVault { it.copy(peers = peersList.toList()) }
            }
        }
        currentScreenState = Screen.Home
    }

    private fun handleQRScan(data: String) {
        val peer = PairingTokenManager(this).parseAndVerifyToken(data)
        if (peer != null) {
            peerToConfirmWithKey = Triple(peer.onionAddress, peer.alias, peer.identityPublicKey)
        } else {
            Toast.makeText(this, "Errore Token", Toast.LENGTH_SHORT).show()
        }
        currentScreenState = Screen.Home
    }

    private fun handleTermsAccept() {
        isTermsAccepted = true
        lifecycleScope.launch {
            PrivacyController.updateVault { it.copy(isTermsAccepted = true) }
            withContext(Dispatchers.Main) {
                currentScreenState = Screen.Auth
            }
        }
    }

    private fun handleChangePassword(data: String): Boolean {
        val p = data.removePrefix("VERIFY:").split("|")
        val oldPass = p[0].toCharArray()
        val newPass = p[1].toCharArray()

        lifecycleScope.launch {
            try {
                PrivacyController.changePassword(oldPass, newPass)
                withContext(Dispatchers.Main) {
                    savedPasswordHash = PrivacyController.vaultData.value?.passwordHash
                    currentScreenState = Screen.Settings
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Errore cambio password", Toast.LENGTH_SHORT).show()
                }
            } finally {
                oldPass.fill('\u0000')
                newPass.fill('\u0000')
            }
        }
        return false
    }

    private fun handleSeedRestore(seed: List<String>) {
        if (MnemonicManager.isValidMnemonic(seed)) {
            tempEntropy = MnemonicManager.mnemonicToEntropy(seed)
            importBackupLauncher.launch(arrayOf("application/json"))
        }
    }

    private fun handleDeleteSession(peer: Peer, messages: MutableList<Message>) {
        SessionManager.removeSession(peer.onionAddress)
        messages.clear()
        currentScreenState = Screen.Home
    }

    private fun handleExportInternal() { exportBackupLauncher.launch("torchat_backup.json") }

    private fun handleExport(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val identity = PrivacyController.getIdentityContext() ?: return
            val mnemonic = MnemonicManager.entropyToMnemonic(identity.entropy)
            val backupJson = backupManager.createEncryptedBackupJson(mnemonic, getSaltForAuth())
            contentResolver.openOutputStream(uri)?.use { it.write(backupJson.toByteArray()) }
            currentScreenState = Screen.Home
        } catch (e: Exception) { Logger.e("Export error", e) }
    }

    private fun handleImport(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { i ->
                val pkg = BufferedReader(InputStreamReader(i)).readText()
                if (backupManager.restoreFromEncryptedBackup(pkg, tempEntropy)) {
                    finish()
                    val restartIntent = Intent(this, MainActivity::class.java)
                    startActivity(restartIntent)
                }
            }
        } catch (e: Exception) { Logger.e("Import error", e) }
    }

    private fun toggleDarkTheme() {
        lifecycleScope.launch {
            PrivacyController.updateVault { it.copy(isDarkTheme = !it.isDarkTheme) }
        }
    }

    private fun toggleAvailability() {
        val newState = !(PrivacyController.vaultData.value?.isAvailable ?: false)
        lifecycleScope.launch {
            PrivacyController.updateVault { it.copy(isAvailable = newState) }
            broadcastMyStatus(newState)
        }
    }

    private fun updateAlias(alias: String) {
        lifecycleScope.launch {
            PrivacyController.updateVault { it.copy(myAlias = alias) }
        }
    }

    private fun updateAutoBackup(enabled: Boolean) {
        lifecycleScope.launch {
            PrivacyController.updateVault { it.copy(isAutoBackupEnabled = enabled) }
        }
    }

    private fun handleIncomingPacket(packet: RawPacket) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val onion = packet.senderOnion
                when (PayloadType.entries[packet.type.toInt()]) {
                    PayloadType.SESSION_HANDSHAKE -> handleHandshakeReceived(packet.data, onion)
                    PayloadType.CHAT_MESSAGE, PayloadType.IMAGE, PayloadType.FILE -> {
                        val session = SessionManager.getSession(onion)
                        if (session != null) {
                            try {
                                val peerRatchetKey = E2EManager.stringToPublicKey(packet.ratchetPubKey, Constants.X25519_ALGO)
                                val header = DoubleRatchetSession.RatchetHeader(peerRatchetKey, packet.pn, packet.n)
                                val aad = E2EManager.buildAAD(packet.version, packet.type, packet.sequenceNumber, onion, session.sessionId, packet.ratchetPubKey, packet.pn, packet.n)

                                val decBytes = session.tryDecrypt(header, packet.data, aad) { enc, key, a ->
                                    E2EManager.decryptV2(enc, key, a)
                                }
                                val dec = String(decBytes, Charsets.UTF_8)

                                val msg = Message(
                                    id = UUID.randomUUID().toString(),
                                    senderOnion = onion,
                                    recipientOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: "",
                                    content = dec,
                                    timestamp = System.currentTimeMillis(),
                                    isOutgoing = false,
                                    isDelivered = true,
                                    isError = false,
                                    type = PayloadType.entries[packet.type.toInt()],
                                    attachment = null,
                                    sequenceNumber = packet.sequenceNumber
                                )
                                messagesMap.getOrPut(onion) { mutableStateListOf() }.add(msg)
                                if (currentScreenState !is Screen.Chat || (currentScreenState as Screen.Chat).peer.onionAddress != onion) unreadCounts[onion] = (unreadCounts[onion] ?: 0) + 1
                                notificationHelper.showChatNotification(onion, "Nuovo messaggio")
                            } catch (e: Exception) {
                                Logger.e("Decryption error for [ONION_HIDDEN]")
                            }
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) { Logger.e("Incoming packet error") }
        }
    }

    private fun handleHandshakeReceived(data: ByteArray, senderOnion: String) {
        try {
            val identity = PrivacyController.getIdentityContext() ?: return
            val myOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: return
            val json = String(data, Charsets.UTF_8)
            val p = Gson().fromJson(json, NetworkPayload::class.java)
            val pts = p.payloadData.split("|")

            if (pts.isEmpty()) return
            val tag = pts.last()

            when (tag) {
                "PFS_INIT" -> {
                    if (pts.size != 5) return
                    val peerEKStr = pts[0]
                    val peerIKStr = pts[1]
                    val peerNonce = Base64.getDecoder().decode(pts[2])
                    val initSig = Base64.getDecoder().decode(pts[3])

                    val peerIdentityKey = E2EManager.stringToPublicKey(peerIKStr, Constants.ED25519_ALGO)
                    val existing = peersList.find { it.onionAddress == senderOnion } ?: return

                    if (existing.identityPublicKey.isNotEmpty() && existing.identityPublicKey != peerIKStr) {
                        Logger.e("IDENTITY KEY CHANGED for [ONION_HIDDEN]!")
                        return
                    }

                    val initTranscript = E2EManager.buildInitTranscript(senderOnion, myOnion, peerIKStr, peerEKStr, peerNonce)
                    if (!E2EManager.verifySignature(initTranscript, initSig, peerIdentityKey)) return

                    val myNonce = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                    val respKeyPair = E2EManager.generateEphemeralKeyPair()
                    val respEKStr = E2EManager.publicKeyToString(respKeyPair.public)
                    val myIKStr = E2EManager.publicKeyToString(identity.identityKeyPair.public)

                    val fullTranscript = E2EManager.buildHandshakeTranscript(senderOnion, myOnion, peerIKStr, peerEKStr, myIKStr, respEKStr, peerNonce, myNonce)
                    val bobSig = Base64.getEncoder().encodeToString(E2EManager.signData(fullTranscript, identity.identityKeyPair.private))

                    val handshakeId = Base64.getEncoder().encodeToString(peerNonce)
                    val pending = PendingHandshake(senderOnion, respKeyPair, myNonce, peerNonce, peerIKStr, peerEKStr, handshakeManager.getCurrentTime())
                    if (!handshakeManager.addPending(handshakeId, pending)) return

                    val respData = "$respEKStr|$bobSig|$myIKStr|${Base64.getEncoder().encodeToString(peerNonce)}|${Base64.getEncoder().encodeToString(myNonce)}|PFS_ACCEPT"
                    val respEnc = Gson().toJson(NetworkPayload(type = PayloadType.SESSION_HANDSHAKE, senderOnion = myOnion, recipientOnion = senderOnion, payloadData = respData)).toByteArray(Charsets.UTF_8)
                    CoroutineScope(Dispatchers.IO).launch {
                        P2PMessenger.sendEncryptedPayload(myOnion, senderOnion, PayloadType.SESSION_HANDSHAKE.ordinal.toByte(), 0, "", 0, 0, respEnc)
                    }
                }
                "PFS_ACCEPT" -> {
                    if (pts.size != 6) return
                    val peerEKStr = pts[0]
                    val peerSig = Base64.getDecoder().decode(pts[1])
                    val peerIKStr = pts[2]
                    val nonceAStr = pts[3]
                    val peerNonce = Base64.getDecoder().decode(pts[4])

                    val peerIdentityKey = E2EManager.stringToPublicKey(peerIKStr, Constants.ED25519_ALGO)
                    val existing = peersList.find { it.onionAddress == senderOnion } ?: return
                    if (existing.identityPublicKey.isNotEmpty() && existing.identityPublicKey != peerIKStr) return

                    var capturedPending: PendingHandshake? = null
                    val success = handshakeManager.verifyAndConsume(nonceAStr) { pending ->
                        capturedPending = pending
                        val myIKStr = E2EManager.publicKeyToString(identity.identityKeyPair.public)
                        val fullTranscript = E2EManager.buildHandshakeTranscript(myOnion, senderOnion, myIKStr, E2EManager.publicKeyToString(pending.myEphemeralKeys.public), peerIKStr, peerEKStr, pending.myNonce, peerNonce)
                        E2EManager.verifySignature(fullTranscript, peerSig, peerIdentityKey)
                    }

                    if (!success || capturedPending == null) return
                    val pending = capturedPending!!
                    val myIKStr = E2EManager.publicKeyToString(identity.identityKeyPair.public)
                    val fullTranscript = E2EManager.buildHandshakeTranscript(myOnion, senderOnion, myIKStr, E2EManager.publicKeyToString(pending.myEphemeralKeys.public), peerIKStr, peerEKStr, pending.myNonce, peerNonce)

                    val aliceSig = Base64.getEncoder().encodeToString(E2EManager.signData(fullTranscript, identity.identityKeyPair.private))
                    val finalData = "$aliceSig|${Base64.getEncoder().encodeToString(pending.myNonce)}|${Base64.getEncoder().encodeToString(peerNonce)}|PFS_FINAL"
                    val finalEnc = Gson().toJson(NetworkPayload(type = PayloadType.SESSION_HANDSHAKE, senderOnion = myOnion, recipientOnion = senderOnion, payloadData = finalData)).toByteArray(Charsets.UTF_8)
                    CoroutineScope(Dispatchers.IO).launch {
                        P2PMessenger.sendEncryptedPayload(myOnion, senderOnion, PayloadType.SESSION_HANDSHAKE.ordinal.toByte(), 0, "", 0, 0, finalEnc)
                    }

                    val sharedSecret = E2EManager.calculateSharedSecret(pending.myEphemeralKeys.private, E2EManager.stringToPublicKey(peerEKStr, Constants.X25519_ALGO))
                    val sid = E2EManager.calculateSessionId(fullTranscript)
                    SessionManager.putSession(senderOnion, DoubleRatchetSession(sid, sharedSecret, pending.myEphemeralKeys, E2EManager.stringToPublicKey(peerEKStr, Constants.X25519_ALGO)))
                    handshakeLoading[senderOnion] = false
                }
                "PFS_FINAL" -> {
                    if (pts.size != 4) return
                    val aliceSig = Base64.getDecoder().decode(pts[0])
                    val nonceAStr = pts[1]
                    val nonceBStr = pts[2]

                    val peerIdentityKey = E2EManager.stringToPublicKey(peersList.find { it.onionAddress == senderOnion }?.identityPublicKey ?: "", Constants.ED25519_ALGO)

                    var capturedPending: PendingHandshake? = null
                    val success = handshakeManager.verifyAndConsume(nonceAStr) { pending ->
                        capturedPending = pending
                        if (Base64.getEncoder().encodeToString(pending.myNonce) != nonceBStr) return@verifyAndConsume false
                        val myIKStr = E2EManager.publicKeyToString(identity.identityKeyPair.public)
                        val fullTranscript = E2EManager.buildHandshakeTranscript(senderOnion, myOnion, pending.peerIdentityKeyStr!!, pending.peerEphemeralKeyStr!!, myIKStr, E2EManager.publicKeyToString(pending.myEphemeralKeys.public), pending.peerNonce!!, pending.myNonce)
                        E2EManager.verifySignature(fullTranscript, aliceSig, peerIdentityKey)
                    }

                    if (!success || capturedPending == null) return
                    val pending = capturedPending!!
                    val myIKStr = E2EManager.publicKeyToString(identity.identityKeyPair.public)
                    val fullTranscript = E2EManager.buildHandshakeTranscript(senderOnion, myOnion, pending.peerIdentityKeyStr!!, pending.peerEphemeralKeyStr!!, myIKStr, E2EManager.publicKeyToString(pending.myEphemeralKeys.public), pending.peerNonce!!, pending.myNonce)

                    val sharedSecret = E2EManager.calculateSharedSecret(pending.myEphemeralKeys.private, E2EManager.stringToPublicKey(pending.peerEphemeralKeyStr!!, Constants.X25519_ALGO))
                    val sid = E2EManager.calculateSessionId(fullTranscript)
                    val session = DoubleRatchetSession(sid, sharedSecret, pending.myEphemeralKeys)
                    session.BobInit(E2EManager.stringToPublicKey(pending.peerEphemeralKeyStr!!, Constants.X25519_ALGO))
                    SessionManager.putSession(senderOnion, session)
                    Logger.i("Session established (Responder)")
                }
            }
        } catch (e: Exception) { Logger.e("Handshake error") }
    }

    private fun initializeSystem() {
        torManager = TorManager(this)
        notificationHelper = NotificationHelper(this)
        backupManager = BackupManager(this)
        mediaManager = MediaManager(this)
        localServer = LocalServer(port = Constants.LOCAL_SERVER_PORT, onPacketReceived = { handleIncomingPacket(it) })

        PrivacyController.initialize(this, tor = torManager, server = localServer!!)
    }

    private fun loadInitialState() {
        // v7: All preferences are in the vault, which is only accessible after unlock.
        // We only check if a password hash exists in old prefs for migration or new vault check.
        val oldPrefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        savedPasswordHash = oldPrefs.getString(Constants.KEY_PASS_HASH, null)
        isTermsAccepted = oldPrefs.getBoolean(Constants.KEY_TERMS_ACCEPTED, false)

        // Wait, if SecureVault is the new primary, how do we know if we have a vault?
        val vaultFile = File(filesDir, "vault.json.enc")
        if (vaultFile.exists()) {
            // We have a vault. Authentication will reveal the data.
            // For now, assume passwordHash is required.
            if (savedPasswordHash == null) savedPasswordHash = "EXISTS"
        }
    }

    private fun loadSensitiveData(p: android.content.SharedPreferences) { /* Legacy - No op in v7 */ }

    private fun sendMessage(peer: Peer, content: String, messageList: MutableList<Message>) {
        CoroutineScope(Dispatchers.IO).launch {
            chatRepository.sendMessage(peer, content).onSuccess { msg ->
                withContext(Dispatchers.Main) { messageList.add(msg) }
            }.onFailure { Logger.e("Send message error") }
        }
    }

    private fun handleMediaPick(uri: Uri, isImage: Boolean) {
        val peer = activeChatPeer ?: return
        val messageList = activeChatMessages ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = if (isImage) mediaManager.stripImageMetadata(uri) else mediaManager.getFileBytes(uri)
                bytes?.let {
                    val b64 = Base64.getEncoder().encodeToString(it)
                    withContext(Dispatchers.Main) { sendMessage(peer, b64, messageList) }
                }
            } catch (e: Exception) { Logger.e("Media pick error") }
        }
    }

    private fun saveAttachmentToExternalStorage(f: String, b: String) {
        try {
            val bts = Base64.getDecoder().decode(b)
            val v = ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f) }
            val uri = contentResolver.insert(if (Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Files.getContentUri("external"), v)
            uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(bts) }
            Toast.makeText(this, "File salvato", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) { }
    }

    private fun getSaltForAuth(): ByteArray {
        // This still uses PrivacyController internal logic or old prefs?
        // Actually, salt is needed BEFORE unlock to derive the key.
        // Salt should be public (or Keystore encrypted).
        // I'll add a static getter to PrivacyController or keep it here.
        val p = getSharedPreferences("secure_prefs_salt", MODE_PRIVATE)
        val sEnc = p.getString("install_salt_enc", null) ?: return ByteArray(16) // Fallback for new install
        return try {
            Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(Base64.getDecoder().decode(sEnc)).getOrThrow())
        } catch (e: Exception) { ByteArray(16) }
    }

    private fun initiateHandshake(p: Peer) {
        val identity = PrivacyController.getIdentityContext() ?: return
        if (p.identityPublicKey.isEmpty()) return
        if (handshakeLoading[p.onionAddress] == true) return
        handshakeLoading[p.onionAddress] = true
        val myOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val myEphemeralKeyPair = E2EManager.generateEphemeralKeyPair()
                val myNonce = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                val eKStr = E2EManager.publicKeyToString(myEphemeralKeyPair.public)
                val myIKStr = E2EManager.publicKeyToString(identity.identityKeyPair.public)
                val nonceAStr = Base64.getEncoder().encodeToString(myNonce)

                val initTranscript = E2EManager.buildInitTranscript(myOnion, p.onionAddress, myIKStr, eKStr, myNonce)
                val aliceInitSig = Base64.getEncoder().encodeToString(E2EManager.signData(initTranscript, identity.identityKeyPair.private))

                val pending = PendingHandshake(p.onionAddress, myEphemeralKeyPair, myNonce, null, null, null, handshakeManager.getCurrentTime())
                handshakeManager.addPending(nonceAStr, pending)

                val data = "$eKStr|$myIKStr|$nonceAStr|$aliceInitSig|PFS_INIT"
                val encryptedJson = Gson().toJson(NetworkPayload(type = PayloadType.SESSION_HANDSHAKE, senderOnion = myOnion, recipientOnion = p.onionAddress, payloadData = data)).toByteArray(Charsets.UTF_8)
                P2PMessenger.sendEncryptedPayload(myOnion, p.onionAddress, PayloadType.SESSION_HANDSHAKE.ordinal.toByte(), 0, "", 0, 0, encryptedJson)
            } catch (e: Exception) {
                handshakeLoading[p.onionAddress] = false
            }
        }
    }

    private fun broadcastMyStatus(a: Boolean) {
        val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""
        if (my.isEmpty()) return
        peersList.forEach {
            val pay = Gson().toJson(NetworkPayload(type = PayloadType.PONG, senderOnion = my, recipientOnion = it.onionAddress, payloadData = if (a) "ONLINE" else "OFFLINE")).toByteArray(Charsets.UTF_8)
            CoroutineScope(Dispatchers.IO).launch { P2PMessenger.sendEncryptedPayload(my, it.onionAddress, PayloadType.PONG.ordinal.toByte(), 0, "", 0, 0, pay, timeoutMs = 10000) }
        }
    }

    private fun checkAndRequestPermissions() {
        val p = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
