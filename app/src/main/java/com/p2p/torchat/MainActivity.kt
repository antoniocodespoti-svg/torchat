package com.p2p.torchat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.crypto.MnemonicManager
import com.p2p.torchat.model.*
import com.p2p.torchat.service.*
import com.p2p.torchat.ui.screens.*
import com.p2p.torchat.ui.theme.TorP2PChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

sealed class Screen {
    object Auth : Screen(); object Home : Screen(); object Settings : Screen()
    object ChangePassword : Screen(); object SeedBackup : Screen(); object SeedRestore : Screen()
    object Subscription : Screen(); object Info : Screen(); object TermsOfUse : Screen()
    data class Chat(val peer: Peer) : Screen(); data class Verification(val peer: Peer) : Screen()
    object QRCode : Screen(); object QRScanner : Screen()
}

class MainActivity : ComponentActivity() {
    companion object { private const val TAG = "MainActivity" }

    private lateinit var torManager: TorManager; private lateinit var localServer: LocalServer
    private lateinit var p2pMessenger: P2PMessenger; private lateinit var notificationHelper: NotificationHelper
    private lateinit var backupManager: BackupManager; private lateinit var mediaManager: MediaManager
    private val totpManager = TotpManager(); private val timeFetcher = NetworkTimeFetcher()

    private lateinit var myKeyPair: KeyPair
    private val sessionKeys = mutableStateMapOf<String, javax.crypto.SecretKey>()
    private val handshakeLoading = mutableStateMapOf<String, Boolean>()
    private val messageCounters = mutableMapOf<String, Int>()
    private var noiseJob: Job? = null

    private val peersList = mutableStateListOf<Peer>()
    private val messagesMap = mutableStateMapOf<String, MutableList<Message>>()
    private val unreadCounts = mutableStateMapOf<String, Int>()

    private var currentScreen by mutableStateOf<Screen>(Screen.Auth)
    private var isAuthenticated by mutableStateOf(false)
    private var isTermsAccepted by mutableStateOf(false)
    private var savedPasswordHash by mutableStateOf<String?>(null)
    private var failedAttempts by mutableStateOf(0)
    private var currentSeed by mutableStateOf<List<String>>(emptyList())

    private var myAlias by mutableStateOf("Amico")
    private var isDarkTheme by mutableStateOf(true)
    private var isAutoBackupEnabled by mutableStateOf(false)
    private var isAvailable by mutableStateOf(false)
    private var expiryDate by mutableStateOf(0L)
    private var expiryAlertMessage by mutableStateOf<String?>(null)
    private var peerToConfirmWithKey by mutableStateOf<Triple<String, String, String>?>(null)
    private var pendingConnectionRequest by mutableStateOf<NetworkPayload?>(null)

    private var activeChatPeer: Peer? = null
    private var activeChatMessages: MutableList<Message>? = null

    // Activity Result Launchers
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { handleMediaPick(it, true) } }
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { handleMediaPick(it, false) } }
    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> u?.let { handleExport(it) } }
    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { handleImport(it) } }
    private val orbotLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res -> if (res.resultCode == RESULT_OK) res.data?.getStringExtra("onion_address")?.let { torManager.setTorRunning(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        initializeSystem(); loadPreferences()
        localServer = LocalServer(port = 8080, onMessageReceived = { handleIncomingPayload(it) })
        if (torManager.isOrbotInstalled()) {
            val s = getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).getString("saved_onion_address", null)
            if (s != null) torManager.setTorRunning(s) else orbotLauncher.launch(torManager.getOrbotRequestIntent())
        }
        localServer.startServer(); startPresenceLoop(peersList)
        setContent { TorP2PChatTheme(darkTheme = isDarkTheme) { AppContent() } }
    }

    @Composable
    private fun AppContent() {
        val torState by torManager.torState.collectAsState()
        val myOnion = (torState as? TorState.Running)?.onionAddress ?: ""
        HandleExpiryCheck(torState)
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val screen = currentScreen) {
                is Screen.Auth -> AuthScreen(mode = if (savedPasswordHash == null) AuthMode.CREATE else AuthMode.LOGIN, attemptsLeft = 3 - failedAttempts, onAuthSuccess = { handleAuthResult(it) })
                is Screen.Subscription -> SubscriptionScreen(onionAddress = myOnion, onActivate = { handleSubscription(it, myOnion) })
                is Screen.Home -> HomeScreen(torState = torState, myOnionAddress = myOnion, myAlias = myAlias, myPublicKey = E2EManager.publicKeyToString(myKeyPair.public), isDarkTheme = isDarkTheme, isAvailable = isAvailable, expiryDate = expiryDate, peers = peersList, unreadCounts = unreadCounts, peerToConfirm = peerToConfirmWithKey, onToggleTheme = { isDarkTheme = !isDarkTheme; saveThemePreference(isDarkTheme) }, onToggleAvailability = { isAvailable = !isAvailable; saveAvailabilityPreference(isAvailable); broadcastMyStatus(isAvailable, peersList) }, onUpdateMyAlias = { myAlias = it; saveMyAlias(it) }, onAddPeerDirect = { a, o, k -> handleAddPeer(a, o, k) }, onSelectPeer = { handleSelectPeer(it) }, onOpenQRCode = { currentScreen = Screen.QRCode }, onOpenQRScanner = { currentScreen = Screen.QRScanner }, onOpenSettings = { currentScreen = Screen.Settings }, onUpdateOnionAddress = { torManager.setTorRunning(it) }, onDeletePeer = { peersList.remove(it); savePeers(peersList) }, onConfirmPeerHandled = { peerToConfirmWithKey = null })
                is Screen.Chat -> ChatScreenContent(screen.peer); is Screen.QRCode -> QRCodeScreen(onionAddress = myOnion, myAlias = myAlias, myPublicKey = E2EManager.publicKeyToString(myKeyPair.public), onBack = { currentScreen = Screen.Home })
                is Screen.Verification -> VerificationScreen(peer = screen.peer, onVerify = { handleVerifyPeer(screen.peer) }, onBack = { currentScreen = Screen.Chat(screen.peer) })
                is Screen.QRScanner -> ClientQRScannerScreen(onScanSuccess = { handleQRScan(it) }, onBack = { currentScreen = Screen.Home })
                is Screen.Settings -> SettingsScreen(onExportBackup = { currentScreen = Screen.SeedBackup }, onImportBackup = { currentScreen = Screen.SeedRestore }, isAutoBackupEnabled = isAutoBackupEnabled, onToggleAutoBackup = { isAutoBackupEnabled = it; saveAutoBackupPreference(it) }, onChangePassword = { currentScreen = Screen.ChangePassword }, onExtendLicense = { currentScreen = Screen.Subscription }, expiryDate = expiryDate, onOpenInfo = { currentScreen = Screen.Info }, onOpenTerms = { currentScreen = Screen.TermsOfUse }, onBack = { currentScreen = Screen.Home })
                is Screen.TermsOfUse -> TermsOfUseScreen(isViewOnly = isTermsAccepted, onAccept = { handleTermsAccept() }, onBack = { currentScreen = Screen.Settings })
                is Screen.Info -> InfoScreen(onBack = { currentScreen = Screen.Settings })
                is Screen.ChangePassword -> AuthScreen(mode = AuthMode.CHANGE, onAuthSuccess = { handleChangePassword(it) }, onBack = { currentScreen = Screen.Settings })
                is Screen.SeedBackup -> SeedBackupContent(); is Screen.SeedRestore -> SeedScreen(SeedMode.INPUT, emptyList(), { handleSeedRestore(it) }, { currentScreen = Screen.Settings })
            }
            DialogsContent()
        }
    }

    @Composable private fun ChatScreenContent(p: Peer) {
        val ms = messagesMap.getOrPut(p.onionAddress) { mutableStateListOf() }
        LaunchedEffect(p.onionAddress) { if (!sessionKeys.containsKey(p.onionAddress)) initiateHandshake(p) }
        ChatScreen(p, ms, handshakeLoading[p.onionAddress] ?: false, { sendMessage(p, it, PayloadType.CHAT_MESSAGE, null, ms) }, { activeChatPeer = p; activeChatMessages = ms; pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, { activeChatPeer = p; activeChatMessages = ms; pickFileLauncher.launch("*/*") }, { f, b -> saveAttachmentToExternalStorage(f, b) }, { handleDeleteSession(p, ms) }, { currentScreen = Screen.Verification(p) }, { currentScreen = Screen.Home })
    }

    @Composable private fun SeedBackupContent() {
        if (currentSeed.isEmpty() && getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).getString("persistent_backup_key", null) == null) currentSeed = MnemonicManager.generateMnemonic()
        SeedScreen(SeedMode.DISPLAY, currentSeed, { exportBackupLauncher.launch("torchat_backup.json") }, { val p = getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE); if (isAuthenticated && (currentSeed.isNotEmpty() || p.getString("persistent_backup_key", null) != null)) currentScreen = Screen.Home else currentScreen = Screen.Settings }, { currentScreen = Screen.Home }, { handleRemoveSeed() })
    }

    private fun handleAuthResult(password: String): Boolean {
        if (password == "WIPE_NOW") { performWipe(); return false }
        if (password.startsWith("CREATE:")) { try { val p = password.removePrefix("CREATE:").split("|"); val h = E2EManager.hashPassword(p[0]); savePasswordHash(h); savedPasswordHash = h; torManager.setTorRunning(p[1]); completeAuth(); currentScreen = Screen.SeedBackup; return true } catch (e: Exception) { return false } }
        if (E2EManager.verifyPassword(password, savedPasswordHash ?: "")) { completeAuth(); currentScreen = Screen.Home; return true }
        failedAttempts++; saveFailedAttempts(failedAttempts); if (failedAttempts >= 3) performWipe(); return false
    }

    private fun completeAuth() { isAuthenticated = true; startNoiseGenerator(); failedAttempts = 0; saveFailedAttempts(0) }
    private fun handleSubscription(code: String, onion: String) { CoroutineScope(Dispatchers.IO).launch { val netTime = timeFetcher.fetchTimeViaTor() ?: System.currentTimeMillis(); val matchedDays = totpManager.findMatchingClientDuration(code, onion, netTime); if (matchedDays != null) { val newExpiry = maxOf(netTime, expiryDate) + (matchedDays.toLong() * 24 * 60 * 60 * 1000); saveExpiryDate(newExpiry); expiryDate = newExpiry; withContext(Dispatchers.Main) { currentScreen = Screen.Home; Toast.makeText(this@MainActivity, "OK", Toast.LENGTH_SHORT).show() } } } }
    private fun handleAddPeer(alias: String, onion: String, pubKey: String) { val clean = sanitizeOnionAddress(onion); if (peersList.any { it.onionAddress == clean }) Toast.makeText(this, "ESISTE", Toast.LENGTH_SHORT).show() else { peersList.add(Peer(clean, alias, pubKey)); savePeers(peersList) } }
    private fun handleSelectPeer(peer: Peer) { unreadCounts[peer.onionAddress] = 0; if (!sessionKeys.containsKey(peer.onionAddress)) initiateHandshake(peer); currentScreen = Screen.Chat(peer) }
    private fun handleVerifyPeer(peer: Peer) { val idx = peersList.indexOf(peer); if (idx != -1) { peersList[idx] = peer.copy(isVerified = true); savePeers(peersList) }; currentScreen = Screen.Home }
    private fun handleQRScan(data: String) { val p = data.split("|"); peerToConfirmWithKey = Triple(sanitizeOnionAddress(p[0]), if (p.size > 1) p[1] else "Amico", if (p.size > 2) p[2] else ""); currentScreen = Screen.Home }
    private fun handleTermsAccept() { isTermsAccepted = true; getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_terms_accepted", true).putLong("terms_accepted_timestamp", System.currentTimeMillis()).apply(); currentScreen = Screen.Auth }
    private fun handleChangePassword(data: String): Boolean { if (!data.startsWith("VERIFY:")) return false; val p = data.removePrefix("VERIFY:").split("|"); if (E2EManager.hashPassword(p[0]) == savedPasswordHash) { val h = E2EManager.hashPassword(p[1]); savePasswordHash(h); savedPasswordHash = h; currentScreen = Screen.Settings; return true } else return false }
    private fun handleSeedRestore(seed: List<String>) { if (MnemonicManager.isValidMnemonic(seed)) { currentSeed = seed; importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) } }
    private fun handleDeleteSession(peer: Peer, messages: MutableList<Message>) { CoroutineScope(Dispatchers.IO).launch { p2pMessenger.sendPayloadOverTor(peer.onionAddress, NetworkPayload(type = PayloadType.SESSION_TERMINATE, senderOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: "", recipientOnion = peer.onionAddress, payloadData = "TERMINATE")) }; messages.clear(); sessionKeys.remove(peer.onionAddress); handshakeLoading.remove(peer.onionAddress); currentScreen = Screen.Home }
    private fun handleRemoveSeed() { if (currentSeed.isNotEmpty()) { val salt = getOrCreateSalt(); val key = MnemonicManager.deriveKeyFromMnemonic(currentSeed, salt); val keyBase64 = Base64.getEncoder().encodeToString(key.encoded); getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().apply { putString("persistent_backup_key", keyBase64); remove("saved_seed"); apply() }; currentSeed = emptyList(); Toast.makeText(this, "RIMOSSO", Toast.LENGTH_SHORT).show() } }
    private fun handleExport(uri: Uri) { try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION); getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putString("last_backup_uri", uri.toString()).apply(); val salt = getOrCreateSalt(); contentResolver.openOutputStream(uri)?.use { it.write(backupManager.createEncryptedBackupJson(currentSeed, salt).toByteArray()) }; if (currentScreen == Screen.SeedBackup) currentScreen = Screen.Home else currentScreen = Screen.Settings } catch (e: Exception) { } }
    private fun handleImport(uri: Uri) { try { contentResolver.openInputStream(uri)?.use { i -> val pkg = BufferedReader(InputStreamReader(i)).readText(); if (backupManager.restoreFromEncryptedBackup(pkg, currentSeed)) { finish(); startActivity(intent) } } } catch (e: Exception) { } }

    private fun handleIncomingPayload(payload: NetworkPayload) {
        when (payload.type) {
            PayloadType.CHAT_MESSAGE, PayloadType.IMAGE, PayloadType.FILE -> {
                if (isAvailable) try {
                    val sK = sessionKeys[payload.senderOnion]
                    if (sK != null) {
                        val dec = E2EManager.decrypt(payload.payloadData, sK)
                        val incomingMsg = Message(senderOnion = payload.senderOnion, recipientOnion = payload.recipientOnion, content = dec, isOutgoing = false, isDelivered = true, type = payload.type, attachment = payload.attachmentMetadata)
                        messagesMap.getOrPut(payload.senderOnion) { mutableStateListOf() }.add(incomingMsg)
                        val c = (messageCounters[payload.senderOnion] ?: 0) + 1; messageCounters[payload.senderOnion] = c; if (c >= 5) triggerSessionRotation(peersList.find { it.onionAddress == payload.senderOnion } ?: return)
                        if (currentScreen !is Screen.Chat || (currentScreen as Screen.Chat).peer.onionAddress != payload.senderOnion) unreadCounts[payload.senderOnion] = (unreadCounts[payload.senderOnion] ?: 0) + 1
                        notificationHelper.showChatNotification(peersList.find { it.onionAddress == payload.senderOnion }?.alias ?: "Peer", dec)
                    }
                } catch (e: Exception) { Log.e(TAG, "Payload error", e) }
            }
            PayloadType.SESSION_HANDSHAKE -> if (isAvailable) handleHandshakeReceived(payload)
            PayloadType.SESSION_TERMINATE -> { sessionKeys.remove(payload.senderOnion); messagesMap[payload.senderOnion]?.clear() }
            PayloadType.PING -> { val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""; CoroutineScope(Dispatchers.IO).launch { p2pMessenger.sendPayloadOverTor(payload.senderOnion, NetworkPayload(PayloadType.PONG, my, payload.senderOnion, if (isAvailable) "ONLINE" else "OFFLINE"), timeoutMs = 10000) } }
            PayloadType.PONG -> { val idx = peersList.indexOfFirst { it.onionAddress == payload.senderOnion }; if (idx != -1) peersList[idx] = peersList[idx].copy(isOnline = payload.payloadData == "ONLINE", lastSeenTimestamp = System.currentTimeMillis()) }
            else -> {}
        }
    }

    private fun handleHandshakeReceived(p: NetworkPayload) {
        try {
            val pts = p.payloadData.split("|"); if (pts.size < 3) return
            val existing = peersList.find { it.onionAddress == p.senderOnion } ?: return
            if (existing.handshakePublicKey.isNotEmpty() && existing.handshakePublicKey != pts[2]) { CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "ATTENZIONE: Chiave cambiata!", Toast.LENGTH_LONG).show() }; return }
            if (existing.handshakePublicKey.isEmpty()) { val upd = existing.copy(handshakePublicKey = pts[2]); val idx = peersList.indexOf(existing); if (idx != -1) { peersList[idx] = upd; savePeers(peersList) } }
            val pK = E2EManager.stringToPublicKey(pts[0]); val sKBytes = com.p2p.torchat.crypto.HKDF.deriveKey(E2EManager.getSharedSecret(myKeyPair.private, pK).encoded, null, "SessionKey".toByteArray(), 32)
            sessionKeys[p.senderOnion] = SecretKeySpec(sKBytes, "AES"); handshakeLoading[p.senderOnion] = false
        } catch (e: Exception) { Log.e(TAG, "Handshake error", e) }
    }

    @Composable private fun HandleExpiryCheck(tor: TorState) { LaunchedEffect(isAuthenticated, tor, currentScreen) { if (isAuthenticated && tor is TorState.Running) { val nt = timeFetcher.fetchTimeViaTor() ?: System.currentTimeMillis(); if (nt > expiryDate) { expiryAlertMessage = null; currentScreen = Screen.Subscription } } } }
    @Composable private fun DialogsContent() {
        pendingConnectionRequest?.let { req -> AlertDialog({ pendingConnectionRequest = null }, { Button({ handleAcceptConnection(req) }) { Text("OK") } }, dismissButton = { TextButton({ pendingConnectionRequest = null }) { Text("NO") } }, title = { Text("RICHIESTA") }, text = { Text("ID: ${req.senderOnion.take(15)}...") }) }
        expiryAlertMessage?.let { msg -> AlertDialog({ expiryAlertMessage = null }, { Button({ expiryAlertMessage = null }) { Text("OK") } }, title = { Text("AVVISO") }, text = { Text(msg) }) }
    }

    private fun handleAcceptConnection(req: NetworkPayload) {
        val p = req.payloadData.split("|"); val newP = Peer(req.senderOnion, "Nuovo Amico", if (p.size >= 3) p[2] else ""); peersList.add(newP); savePeers(peersList)
        try { val pK = E2EManager.stringToPublicKey(p[0]); val sKBytes = com.p2p.torchat.crypto.HKDF.deriveKey(E2EManager.getSharedSecret(myKeyPair.private, pK).encoded, null, "SessionKey".toByteArray(), 32); sessionKeys[req.senderOnion] = SecretKeySpec(sKBytes, "AES") } catch (e: Exception) { }
        pendingConnectionRequest = null
    }

    private fun initializeSystem() { checkAndRequestPermissions(); torManager = TorManager(this); p2pMessenger = P2PMessenger(); notificationHelper = NotificationHelper(this); backupManager = BackupManager(this); mediaManager = MediaManager(this) }
    private fun loadPreferences() { val p = getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE); myAlias = p.getString("my_alias", "Amico") ?: "Amico"; isDarkTheme = p.getBoolean("is_dark_theme", true); isAutoBackupEnabled = p.getBoolean("is_auto_backup_enabled", false); isTermsAccepted = p.getBoolean("is_terms_accepted", false); savedPasswordHash = p.getString("app_password_hash", null); failedAttempts = p.getInt("failed_attempts", 0); currentScreen = if (!isTermsAccepted) Screen.TermsOfUse else Screen.Auth; expiryDate = p.getLong("account_expiry_date", 0L); loadOrGenerateIdentityKeys(p); peersList.clear(); peersList.addAll(loadPeers()) }

    private fun sendMessage(peer: Peer, content: String, type: PayloadType, metadata: AttachmentMetadata?, messageList: MutableList<Message>) {
        if (handshakeLoading[peer.onionAddress] == true) return
        val sK = sessionKeys[peer.onionAddress] ?: return initiateHandshake(peer)
        val encrypted = E2EManager.encrypt(content, sK); val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""
        val msg = Message(senderOnion = my, recipientOnion = peer.onionAddress, content = content, isOutgoing = true, type = type, attachment = metadata)
        CoroutineScope(Dispatchers.Main).launch { messageList.add(msg) }
        CoroutineScope(Dispatchers.IO).launch {
            val res = p2pMessenger.sendPayloadOverTor(peer.onionAddress, NetworkPayload(type = type, senderOnion = my, recipientOnion = peer.onionAddress, payloadData = encrypted, attachmentMetadata = metadata))
            if (res.isSuccess) { val c = (messageCounters[peer.onionAddress] ?: 0) + 1; messageCounters[peer.onionAddress] = c; if (c >= 5) triggerSessionRotation(peer) }
            val idx = messageList.indexOf(msg); if (idx != -1) withContext(Dispatchers.Main) { messageList[idx] = msg.copy(isDelivered = res.isSuccess, isError = !res.isSuccess) }
        }
    }

    private fun handleMediaPick(u: Uri, i: Boolean) { val p = activeChatPeer ?: return; val m = activeChatMessages ?: return; CoroutineScope(Dispatchers.IO).launch { val b = if (i) mediaManager.stripImageMetadata(u) else mediaManager.getFileBytes(u); b?.let { val b64 = Base64.getEncoder().encodeToString(it); val d = mediaManager.getFileDetails(u); sendMessage(p, b64, if (i) PayloadType.IMAGE else PayloadType.FILE, AttachmentMetadata(d.first, d.second), m) } } }
    private fun saveAttachmentToExternalStorage(f: String, b: String) { try { val bts = Base64.getDecoder().decode(b); val v = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, f) }; val uri = contentResolver.insert(if (android.os.Build.VERSION.SDK_INT >= 29) android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Files.getContentUri("external"), v); uri?.let { contentResolver.openOutputStream(it)?.use { os -> os.write(bts) }; CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "OK", Toast.LENGTH_SHORT).show() } } } catch (e: Exception) { } }
    private fun sanitizeOnionAddress(o: String): String = o.trim().removePrefix("http://").removePrefix("https://").removeSuffix("/")
    private fun autoBackup() { if (isAutoBackupEnabled && currentSeed.isNotEmpty()) getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).getString("last_backup_uri", null)?.let { try { contentResolver.openOutputStream(it.toUri())?.use { os -> os.write(backupManager.createEncryptedBackupJson(currentSeed, getOrCreateSalt()).toByteArray()) } } catch (e: Exception) { } } }
    private fun loadPeers(): List<Peer> { val p = getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE); val d = p.getString("saved_peers", null) ?: return emptyList(); return try { val json = if (d.startsWith("[")) d else { val h = p.getString("app_password_hash", null); if (h != null) E2EManager.decrypt(d, E2EManager.deriveKeyFromSecret(h)) else d }; Gson().fromJson(json, object : TypeToken<List<Peer>>() {}.type) } catch (e: Exception) { emptyList() } }
    private fun savePeers(peers: List<Peer>) { val p = getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE); val j = Gson().toJson(peers.toList()); try { val h = p.getString("app_password_hash", null); val data = if (h != null) E2EManager.encrypt(j, E2EManager.deriveKeyFromSecret(h)) else j; p.edit().putString("saved_peers", data).apply() } catch (e: Exception) { p.edit().putString("saved_peers", j).apply() } }
    private fun getOrCreateSalt(): ByteArray { val p = getSharedPreferences("secure_prefs_salt", Context.MODE_PRIVATE); val s = p.getString("install_salt_enc", null); return if (s != null) { try { Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(s)) } catch (e: Exception) { generateAndSaveSalt(p) } } else generateAndSaveSalt(p) }
    private fun generateAndSaveSalt(p: android.content.SharedPreferences): ByteArray { val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }; val enc = E2EManager.encryptWithHardwareKey(Base64.getEncoder().encodeToString(s)); p.edit().putString("install_salt_enc", enc).apply(); return s }
    private fun startNoiseGenerator() { noiseJob?.cancel(); noiseJob = CoroutineScope(Dispatchers.IO).launch { while (true) { delay(Random.nextLong(300_000, 900_000)); if (isAvailable && peersList.isNotEmpty()) { val p = peersList.random(); val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""; if (my.isNotEmpty()) p2pMessenger.sendPayloadOverTor(p.onionAddress, NetworkPayload(type = PayloadType.DUMMY_NOISE, senderOnion = my, recipientOnion = p.onionAddress, payloadData = "NOISE_" + UUID.randomUUID())) } } } }
    private fun triggerSessionRotation(p: Peer) { Log.d(TAG, "Rotating key for ${p.alias}"); messageCounters[p.onionAddress] = 0; initiateHandshake(p) }
    private fun saveMyAlias(a: String) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putString("my_alias", a).apply() }
    private fun saveThemePreference(d: Boolean) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_dark_theme", d).apply() }
    private fun saveAvailabilityPreference(a: Boolean) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_available", a).apply() }
    private fun savePasswordHash(h: String) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putString("app_password_hash", h).apply() }
    private fun saveFailedAttempts(a: Int) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putInt("failed_attempts", a).apply() }
    private fun saveExpiryDate(d: Long) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putLong("account_expiry_date", d).apply(); autoBackup() }
    private fun saveAutoBackupPreference(e: Boolean) { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_auto_backup_enabled", e).apply() }
    private fun performWipe() { getSharedPreferences("tor_chat_prefs", Context.MODE_PRIVATE).edit().clear().apply(); try { E2EManager.deleteMasterKey() } catch (e: Exception) { }; val d = File(filesDir, "tor"); if (d.exists()) d.deleteRecursively(); CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "WIPE ESEGUITO", Toast.LENGTH_LONG).show(); finish() } }
    private fun loadOrGenerateIdentityKeys(p: android.content.SharedPreferences) { val pub = p.getString("my_public_key", null); val privEnc = p.getString("my_private_key_enc", null); if (pub != null && privEnc != null) try { val priv = E2EManager.decryptWithHardwareKey(privEnc); val f = KeyFactory.getInstance("EC"); myKeyPair = KeyPair(f.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub))), f.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv)))) } catch (e: Exception) { generateNewIdentityKeys(p) } else generateNewIdentityKeys(p) }
    private fun generateNewIdentityKeys(p: android.content.SharedPreferences) { myKeyPair = E2EManager.generateECDHKeyPair(); val priv = Base64.getEncoder().encodeToString(myKeyPair.private.encoded); val enc = E2EManager.encryptWithHardwareKey(priv); p.edit().apply { putString("my_public_key", E2EManager.publicKeyToString(myKeyPair.public)); putString("my_private_key_enc", enc); remove("my_private_key"); apply() } }
    private fun initiateHandshake(p: Peer) { if (p.handshakePublicKey.isEmpty()) return; if (handshakeLoading[p.onionAddress] == true) return; handshakeLoading[p.onionAddress] = true; CoroutineScope(Dispatchers.IO).launch { try { val eKP = E2EManager.generateECDHKeyPair(); val pPK = E2EManager.stringToPublicKey(p.handshakePublicKey); val tS = E2EManager.getSharedSecret(eKP.private, pPK); val sKBytes = com.p2p.torchat.crypto.HKDF.deriveKey(tS.encoded, null, "SessionKey".toByteArray(), 32); val sK = SecretKeySpec(sKBytes, "AES"); val myID = E2EManager.publicKeyToString(myKeyPair.public); val data = "${E2EManager.publicKeyToString(eKP.public)}|PFS|$myID"; val pay = NetworkPayload(type = PayloadType.SESSION_HANDSHAKE, senderOnion = (torManager.torState.value as? TorState.Running)?.onionAddress ?: "", recipientOnion = p.onionAddress, payloadData = data); val res = p2pMessenger.sendPayloadOverTor(p.onionAddress, pay, timeoutMs = 30000); if (res.isSuccess) sessionKeys[p.onionAddress] = sK } catch (e: Exception) { } finally { handshakeLoading[p.onionAddress] = false } } }
    private fun startPresenceLoop(peers: MutableList<Peer>) { CoroutineScope(Dispatchers.IO).launch { while (true) { val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""; if (my.isNotEmpty()) peers.forEach { val ping = NetworkPayload(type = PayloadType.PING, senderOnion = my, recipientOnion = it.onionAddress, payloadData = ""); val res = p2pMessenger.sendPayloadOverTor(it.onionAddress, ping, timeoutMs = 15000); if (res.isFailure) { val idx = peers.indexOfFirst { p -> p.onionAddress == it.onionAddress }; if (idx != -1) peers[idx] = peers[idx].copy(isOnline = false) } }; delay(60_000L) } } }
    private fun broadcastMyStatus(a: Boolean, p: List<Peer>) { val my = (torManager.torState.value as? TorState.Running)?.onionAddress ?: ""; if (my.isEmpty()) return; p.forEach { val pay = NetworkPayload(type = PayloadType.PONG, senderOnion = my, recipientOnion = it.onionAddress, payloadData = if (a) "ONLINE" else "OFFLINE"); CoroutineScope(Dispatchers.IO).launch { p2pMessenger.sendPayloadOverTor(it.onionAddress, pay, timeoutMs = 10000) } } }
    private fun checkAndRequestPermissions() { val p = mutableListOf(Manifest.permission.CAMERA); if (android.os.Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS); val missing = p.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }; if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) }

    @Composable private fun AuthScreen(mode: AuthMode, attemptsLeft: Int = 3, onAuthSuccess: (String) -> Boolean, onBack: (() -> Unit)? = null) { com.p2p.torchat.ui.screens.AuthScreen(mode = mode, attemptsLeft = attemptsLeft, onAuthSuccess = onAuthSuccess, onBack = onBack) }
    @Composable private fun SubscriptionScreen(onionAddress: String, onActivate: (String) -> Unit) { com.p2p.torchat.ui.screens.SubscriptionScreen(onionAddress = onionAddress, onActivate = onActivate) }
    @Composable private fun HomeScreen(torState: TorState, myOnionAddress: String, myAlias: String, myPublicKey: String, isDarkTheme: Boolean, isAvailable: Boolean, expiryDate: Long, peers: List<Peer>, unreadCounts: Map<String, Int>, peerToConfirm: Triple<String, String, String>? = null, onToggleTheme: () -> Unit, onToggleAvailability: () -> Unit, onUpdateMyAlias: (String) -> Unit, onAddPeerDirect: (String, String, String) -> Unit, onSelectPeer: (Peer) -> Unit, onOpenQRCode: () -> Unit, onOpenQRScanner: () -> Unit, onOpenSettings: () -> Unit, onUpdateOnionAddress: (String) -> Unit, onDeletePeer: (Peer) -> Unit, onConfirmPeerHandled: () -> Unit) { com.p2p.torchat.ui.screens.HomeScreen(torState, myOnionAddress, myAlias, myPublicKey, isDarkTheme, isAvailable, expiryDate, peers, unreadCounts, peerToConfirm, onToggleTheme, onToggleAvailability, onUpdateMyAlias, onAddPeerDirect, onSelectPeer, onOpenQRCode, onOpenQRScanner, onOpenSettings, onUpdateOnionAddress, onDeletePeer, onConfirmPeerHandled) }
    @Composable private fun ChatScreen(peer: Peer, messages: List<Message>, isHandshakeLoading: Boolean, onSendMessage: (String) -> Unit, onPickImage: () -> Unit, onPickFile: () -> Unit, onSaveAttachment: (String, String) -> Unit, onDeleteSession: () -> Unit, onOpenVerification: () -> Unit, onBack: () -> Unit) { com.p2p.torchat.ui.screens.ChatScreen(peer, messages, isHandshakeLoading, onSendMessage, onPickImage, onPickFile, onSaveAttachment, onDeleteSession, onOpenVerification, onBack) }
    @Composable private fun QRCodeScreen(onionAddress: String, myAlias: String, myPublicKey: String, onBack: () -> Unit) { com.p2p.torchat.ui.screens.QRCodeScreen(onionAddress, myAlias, myPublicKey, onBack) }
    @Composable private fun VerificationScreen(peer: Peer, onVerify: () -> Unit, onBack: () -> Unit) { com.p2p.torchat.ui.screens.VerificationScreen(peer, onVerify, onBack) }
    @Composable private fun ClientQRScannerScreen(onScanSuccess: (String) -> Unit, onBack: () -> Unit) { com.p2p.torchat.ui.screens.ClientQRScannerScreen(onScanSuccess, onBack) }
    @Composable private fun SettingsScreen(onExportBackup: () -> Unit, onImportBackup: () -> Unit, isAutoBackupEnabled: Boolean, onToggleAutoBackup: (Boolean) -> Unit, onChangePassword: () -> Unit, onExtendLicense: () -> Unit, expiryDate: Long, onOpenInfo: () -> Unit, onOpenTerms: () -> Unit, onBack: () -> Unit) { com.p2p.torchat.ui.screens.SettingsScreen(onExportBackup, onImportBackup, isAutoBackupEnabled, onToggleAutoBackup, onChangePassword, onExtendLicense, expiryDate, onOpenInfo, onOpenTerms, onBack) }
    @Composable private fun TermsOfUseScreen(isViewOnly: Boolean = false, onAccept: () -> Unit = {}, onBack: () -> Unit = {}) { com.p2p.torchat.ui.screens.TermsOfUseScreen(isViewOnly, onAccept, onBack) }
    @Composable private fun SeedScreen(mode: SeedMode, seed: List<String>, onAction: (List<String>) -> Unit, onBack: () -> Unit, onSkip: () -> Unit = {}, onRemoveSeed: () -> Unit = {}) { com.p2p.torchat.ui.screens.SeedScreen(mode, seed, onAction, onBack, onSkip, onRemoveSeed) }
}
