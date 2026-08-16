package com.p2p.tormaster

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.p2p.tormaster.crypto.E2EManager
import com.p2p.tormaster.crypto.MnemonicManager
import com.p2p.tormaster.service.*
import com.p2p.tormaster.ui.QRUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

sealed class MasterScreen {
    object Auth : MasterScreen()
    object Wallet : MasterScreen()
    object Generator : MasterScreen()
    object Scanner : MasterScreen()
    object Recharge : MasterScreen()
    object SeedBackup : MasterScreen()
    object Info : MasterScreen()
}

class MainActivity : ComponentActivity() {
    companion object { private const val TAG = "MasterActivity" }
    private val totpManager = TotpManager()
    private val torManager by lazy { TorManager(this) }
    private val timeFetcher = NetworkTimeFetcher()
    private lateinit var walletManager: MasterWalletManager
    private var currentScreen by mutableStateOf<MasterScreen>(MasterScreen.Auth)
    private var scannedUtenteId by mutableStateOf("")
    private var masterPasswordHash by mutableStateOf<String?>(null)
    private var isAutoBackupEnabled by mutableStateOf(false)
    private var failedAttempts by mutableStateOf(0)
    private var currentSeed by mutableStateOf<List<String>>(emptyList())

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) currentScreen = MasterScreen.Scanner }
    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> u?.let { handleExport(it) } }
    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { handleImport(it) } }
    private val orbotLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res -> if (res.resultCode == RESULT_OK) res.data?.getStringExtra("onion_address")?.let { torManager.setTorRunning(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletManager = MasterWalletManager(this)
        loadPreferences()
        if (torManager.isOrbotInstalled()) {
            val s = getSharedPreferences("master_prefs", Context.MODE_PRIVATE).getString("saved_onion_address", null)
            if (s != null) torManager.setTorRunning(s) else orbotLauncher.launch(torManager.getOrbotRequestIntent())
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00FFFF))) { AppContent() } }
    }

    @Composable
    private fun AppContent() {
        val ds = rememberDrawerState(DrawerValue.Closed); val sc = rememberCoroutineScope()
        ModalNavigationDrawer(drawerContent = { MasterDrawer(ds, sc) }, drawerState = ds, gesturesEnabled = currentScreen !is MasterScreen.Auth && currentScreen !is MasterScreen.Scanner) {
            Scaffold(topBar = { if (currentScreen !is MasterScreen.Auth && currentScreen !is MasterScreen.Scanner) MasterTopBar(ds, sc) }) { padding ->
                Box(Modifier.padding(padding)) {
                    when (currentScreen) {
                        is MasterScreen.Auth -> MasterAuthScreen()
                        is MasterScreen.Wallet -> WalletScreen()
                        is MasterScreen.Generator -> GeneratorScreen()
                        is MasterScreen.Recharge -> RechargeScreen()
                        is MasterScreen.Scanner -> MasterQRScannerScreen({ scannedUtenteId = it.removePrefix("http://").removePrefix("https://").removeSuffix("/"); currentScreen = MasterScreen.Generator }, { currentScreen = MasterScreen.Generator })
                        is MasterScreen.Info -> MasterInfoScreen()
                        is MasterScreen.SeedBackup -> MasterSeedScreen()
                    }
                }
            }
        }
    }

    private fun handleAuth(p: String, c: String): Boolean {
        if (masterPasswordHash == null) { if (p.isNotEmpty() && p == c) { val h = E2EManager.hashPassword(p); getSharedPreferences("master_prefs", Context.MODE_PRIVATE).edit().putString("master_password_hash", h).apply(); masterPasswordHash = h; currentScreen = MasterScreen.SeedBackup; return true } }
        else if (E2EManager.verifyPassword(p, masterPasswordHash ?: "")) { failedAttempts = 0; getSharedPreferences("master_prefs", Context.MODE_PRIVATE).edit().putInt("failed_attempts", 0).apply(); currentScreen = MasterScreen.Wallet; return true }
        else { failedAttempts++; getSharedPreferences("master_prefs", Context.MODE_PRIVATE).edit().putInt("failed_attempts", failedAttempts).apply(); if (failedAttempts >= 3) performWipe() else Toast.makeText(this, "No (${3 - failedAttempts})", Toast.LENGTH_SHORT).show() }
        return false
    }

    private fun handleExport(u: Uri) { try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION); getSharedPreferences("master_prefs", Context.MODE_PRIVATE).edit().putString("last_backup_uri", u.toString()).apply(); contentResolver.openOutputStream(u)?.use { it.write(MasterBackupManager(this).createEncryptedBackupJson(currentSeed, getOrCreateSalt(), walletManager.getBalance()).toByteArray()) }; Toast.makeText(this, "OK", Toast.LENGTH_SHORT).show() } catch (e: Exception) { } }
    private fun handleImport(u: Uri) { try { val pkg = contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }; if (pkg != null && MasterBackupManager(this).restoreFromEncryptedBackup(pkg, currentSeed) != null) { finish(); startActivity(intent) } } catch (e: Exception) { } }

    private fun loadPreferences() {
        val p = getSharedPreferences("master_prefs", Context.MODE_PRIVATE); masterPasswordHash = p.getString("master_password_hash", null); failedAttempts = p.getInt("failed_attempts", 0); isAutoBackupEnabled = p.getBoolean("is_auto_backup_enabled", false)
        val s = p.getString("master_seed", null); if (s != null) currentSeed = s.split(" ") else { currentSeed = MnemonicManager.generateMnemonic(); p.edit().putString("master_seed", currentSeed.joinToString(" ")).apply() }
    }

    private fun getOrCreateSalt(): ByteArray {
        val p = getSharedPreferences("master_secure_prefs", Context.MODE_PRIVATE); val sEnc = p.getString("install_salt_enc", null)
        return if (sEnc != null) { try { Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(sEnc)) } catch (e: Exception) { generateAndSaveSalt(p) } } else generateAndSaveSalt(p)
    }

    private fun generateAndSaveSalt(p: android.content.SharedPreferences): ByteArray {
        val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val enc = E2EManager.encryptWithHardwareKey(Base64.getEncoder().encodeToString(s))
        p.edit().putString("install_salt_enc", enc).apply()
        return s
    }

    private fun performWipe() { getSharedPreferences("master_prefs", Context.MODE_PRIVATE).edit().clear().apply(); getSharedPreferences("master_wallet_prefs", Context.MODE_PRIVATE).edit().clear().apply(); try { E2EManager.deleteMasterKey() } catch (e: Exception) { }; finish() }

    @OptIn(ExperimentalMaterial3Api::class) @Composable private fun MasterDrawer(ds: DrawerState, sc: CoroutineScope) { ModalDrawerSheet { Spacer(Modifier.height(12.dp)); Text("MASTER WALLET", Modifier.padding(16.dp), Color.Cyan, fontWeight = FontWeight.Bold); NavigationDrawerItem({ Text("WALLET") }, currentScreen == MasterScreen.Wallet, { currentScreen = MasterScreen.Wallet; sc.launch { ds.close() } }, icon = { Icon(Icons.Default.AccountBalanceWallet, null) }); NavigationDrawerItem({ Text("GENERA") }, currentScreen == MasterScreen.Generator, { currentScreen = MasterScreen.Generator; sc.launch { ds.close() } }, icon = { Icon(Icons.Default.VpnKey, null) }); NavigationDrawerItem({ Text("BACKUP") }, currentScreen == MasterScreen.SeedBackup, { currentScreen = MasterScreen.SeedBackup; sc.launch { ds.close() } }, icon = { Icon(Icons.Default.Backup, null) }); Spacer(Modifier.weight(1f)); TextButton({ performWipe() }, Modifier.fillMaxWidth()) { Text("RESET", color = Color.Red) } } }
    @OptIn(ExperimentalMaterial3Api::class) @Composable private fun MasterTopBar(ds: DrawerState, sc: CoroutineScope) { CenterAlignedTopAppBar(title = { Text("TOR MASTER") }, navigationIcon = { IconButton({ sc.launch { ds.open() } }) { Icon(Icons.Default.Menu, null) } }) }

    @Composable fun MasterAuthScreen() {
        var p by remember { mutableStateOf("") }; var c by remember { mutableStateOf("") }; var v by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Security, null, tint = Color.Cyan, modifier = Modifier.size(64.dp))
                OutlinedTextField(p, { p = it }, label = { Text("Password") }, visualTransformation = if (v) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton({ v = !v }) { Icon(if (v) Icons.Default.Visibility else Icons.Default.VisibilityOff, null) } })
                if (masterPasswordHash == null) OutlinedTextField(c, { c = it }, label = { Text("Conferma") }, visualTransformation = if (v) VisualTransformation.None else PasswordVisualTransformation())
                Button({ handleAuth(p, c) }, Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("ENTRA") }
            }
        }
    }

    @Composable fun WalletScreen() {
        val bal = walletManager.getBalance(); val tor by torManager.torState.collectAsState(); val myO = (tor as? TorState.Running)?.onionAddress ?: "..."
        Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$bal GG", style = MaterialTheme.typography.displayMedium, color = Color.White)
            Card(Modifier.padding(top = 16.dp)) { Column(Modifier.padding(16.dp)) { Text("ID MASTER:", color = Color.Cyan); Text(myO, color = Color.White, fontSize = 12.sp) } }
            Button({ currentScreen = MasterScreen.Generator }, Modifier.fillMaxWidth().padding(top = 32.dp)) { Text("SBLOCCA UTENTE") }
        }
    }

    @Composable fun GeneratorScreen() {
        var uid by remember { mutableStateOf(scannedUtenteId) }; var d by remember { mutableIntStateOf(30) }; var code by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
            OutlinedTextField(uid, { uid = it }, label = { Text("ID Utente") }, trailingIcon = { IconButton({ cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) { Icon(Icons.Default.QrCodeScanner, null) } })
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("GIORNI: $d", color = Color.White); Row { IconButton({ d -= 30 }) { Icon(Icons.Default.Remove, null) }; IconButton({ d += 30 }) { Icon(Icons.Default.Add, null) } } }
            Button({ CoroutineScope(Dispatchers.IO).launch { if (walletManager.spendDays(d)) { val nt = timeFetcher.fetchTimeViaTor() ?: System.currentTimeMillis(); code = totpManager.generateClientCode(uid, nt, d) } } }, Modifier.fillMaxWidth()) { Text("GENERA") }
            code?.let { Text(it, Modifier.padding(top = 16.dp), color = Color.Magenta, style = MaterialTheme.typography.displayMedium) }
        }
    }

    @Composable fun RechargeScreen() {
        var c by remember { mutableStateOf("") }; val tor by torManager.torState.collectAsState(); val myO = (tor as? TorState.Running)?.onionAddress ?: ""
        Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
            OutlinedTextField(c, { c = it }, label = { Text("Codice Super (8 cifre)") })
            Button({ CoroutineScope(Dispatchers.IO).launch { val nt = timeFetcher.fetchTimeViaTor() ?: System.currentTimeMillis(); val d = totpManager.findMatchingMasterDuration(c, myO, nt); if (d != null) { walletManager.addDays(d); withContext(Dispatchers.Main) { currentScreen = MasterScreen.Wallet } } } }, Modifier.fillMaxWidth()) { Text("APPLICA") }
        }
    }

    @Composable fun MasterInfoScreen() { Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) { Text("INFO MASTER", color = Color.Cyan); Text("Architettura P2P Tor pura.", color = Color.White) } }

    @Composable fun MasterSeedScreen() {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("IL TUO SEED", color = Color.Magenta)
            Card(Modifier.padding(16.dp)) { Text(currentSeed.joinToString(" "), Modifier.padding(16.dp)) }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                Button({ exportBackupLauncher.launch("master_backup.json") }) { Text("EXPORT") }
                Button({ importBackupLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("IMPORT") }
            }
            OutlinedButton({ currentScreen = MasterScreen.Wallet }, Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("SALTA") }
        }
    }

    private fun saveAutoBackupPreference(e: Boolean) { getSharedPreferences("master_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_auto_backup_enabled", e).apply() }
    override fun onStop() { super.onStop(); autoBackup() }
    private fun autoBackup() { if (isAutoBackupEnabled && currentSeed.isNotEmpty()) getSharedPreferences("master_prefs", Context.MODE_PRIVATE).getString("last_backup_uri", null)?.let { try { contentResolver.openOutputStream(it.toUri())?.use { os -> os.write(MasterBackupManager(this).createEncryptedBackupJson(currentSeed, getOrCreateSalt(), walletManager.getBalance()).toByteArray()) } } catch (e: Exception) { } } }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class) @Composable fun MasterQRScannerScreen(onS: (String) -> Unit, onB: () -> Unit) { val ctx = LocalContext.current; val lo = LocalLifecycleOwner.current; val cpf = remember { ProcessCameraProvider.getInstance(ctx) }; var hs by remember { mutableStateOf(false) }; Box(Modifier.fillMaxSize()) { AndroidView({ c -> val pv = PreviewView(c); cpf.addListener({ val cp = cpf.get(); val ia = ImageAnalysis.Builder().build(); ia.setAnalyzer(Executors.newSingleThreadExecutor()) { ip -> val mi = ip.image; if (mi != null && !hs) { BarcodeScanning.getClient().process(InputImage.fromMediaImage(mi, ip.imageInfo.rotationDegrees)).addOnSuccessListener { for (b in it) b.rawValue?.let { if (!hs) { hs = true; onS(it) } } }.addOnCompleteListener { ip.close() } } else ip.close() }; try { cp.unbindAll(); cp.bindToLifecycle(lo, CameraSelector.DEFAULT_BACK_CAMERA, Preview.Builder().build().apply { setSurfaceProvider(pv.surfaceProvider) }, ia) } catch (e: Exception) {} }, ContextCompat.getMainExecutor(c)); pv }, Modifier.fillMaxSize()); IconButton(onB, Modifier.align(Alignment.TopStart).padding(16.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } } }
