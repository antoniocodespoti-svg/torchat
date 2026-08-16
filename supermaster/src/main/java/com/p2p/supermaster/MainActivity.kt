package com.p2p.supermaster

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.p2p.supermaster.crypto.E2EManager
import com.p2p.supermaster.crypto.MnemonicManager
import com.p2p.supermaster.service.*
import com.p2p.supermaster.ui.QRUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

data class RechargeEvent(val timestamp: Long, val days: Int)
data class MasterCollaborator(val username: String, val onionAddress: String, val addedTimestamp: Long = System.currentTimeMillis(), val isStandBy: Boolean = false, val rechargeHistory: List<RechargeEvent> = emptyList())

sealed class SuperScreen {
    object Auth : SuperScreen(); object Home : SuperScreen(); object Directory : SuperScreen()
    data class Details(val onion: String) : SuperScreen(); object Scanner : SuperScreen(); object SeedBackup : SuperScreen()
}

class MainActivity : ComponentActivity() {
    companion object { private const val TAG = "SuperActivity" }
    private val totpManager = TotpManager(); private val torManager by lazy { TorManager(this) }; private val timeFetcher = NetworkTimeFetcher(); private val gson = Gson(); private val backupManager by lazy { SuperBackupManager(this) }
    private var currentScreen by mutableStateOf<SuperScreen>(SuperScreen.Auth); private val collaborators = mutableStateListOf<MasterCollaborator>(); private var selectedMasterId by mutableStateOf(""); private var masterPasswordHash by mutableStateOf<String?>(null); private var isAutoBackupEnabled by mutableStateOf(false); private var failedAttempts by mutableStateOf(0); private var currentSeed by mutableStateOf<List<String>>(emptyList()); private var isScanningForDirectory by mutableStateOf(false)

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) currentScreen = SuperScreen.Scanner }
    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> u?.let { handleExport(it) } }
    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { handleImport(it) } }
    private val orbotLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res -> if (res.resultCode == RESULT_OK) res.data?.getStringExtra("onion_address")?.let { torManager.setTorRunning(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPreferences()
        if (torManager.isOrbotInstalled()) {
            val s = getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE).getString("saved_onion_address", null)
            if (s != null) torManager.setTorRunning(s) else orbotLauncher.launch(torManager.getOrbotRequestIntent())
        }
        torManager.startTorService()
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color.Yellow)) { AppContent() } }
    }

    @Composable
    private fun AppContent() {
        val ds = rememberDrawerState(DrawerValue.Closed); val sc = rememberCoroutineScope()
        ModalNavigationDrawer(drawerContent = { SuperDrawer(ds, sc) }, drawerState = ds, gesturesEnabled = currentScreen !is SuperScreen.Auth && currentScreen !is SuperScreen.Scanner) {
            Scaffold(topBar = { if (currentScreen !is SuperScreen.Auth && currentScreen !is SuperScreen.Scanner) SuperTopBar(ds, sc) }) { padding ->
                Box(Modifier.padding(padding)) {
                    when (val screen = currentScreen) {
                        is SuperScreen.Auth -> SuperAuthScreen()
                        is SuperScreen.Home -> SuperHomeScreen()
                        is SuperScreen.Directory -> DirectoryScreen()
                        is SuperScreen.Details -> MasterDetailsScreen(screen.onion)
                        is SuperScreen.Scanner -> ImperialScannerUI({ onion -> val clean = onion.removePrefix("http://").removePrefix("https://").split("|")[0].removeSuffix("/"); selectedMasterId = clean; currentScreen = if (isScanningForDirectory) SuperScreen.Directory else SuperScreen.Home }, { currentScreen = if (isScanningForDirectory) SuperScreen.Directory else SuperScreen.Home })
                        is SuperScreen.SeedBackup -> SuperSeedScreen()
                    }
                }
            }
        }
    }

    private fun handleAuth(p: String, c: String) {
        if (masterPasswordHash == null) { if (p.isNotEmpty() && p == c) { val h = E2EManager.hashPassword(p); getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE).edit().putString("super_password_hash", h).apply(); masterPasswordHash = h; currentScreen = SuperScreen.SeedBackup } }
        else if (E2EManager.verifyPassword(p, masterPasswordHash ?: "")) { failedAttempts = 0; getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE).edit().putInt("failed_attempts", 0).apply(); currentScreen = SuperScreen.Directory }
        else { failedAttempts++; getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE).edit().putInt("failed_attempts", failedAttempts).apply(); if (failedAttempts >= 3) performWipe() else Toast.makeText(this, "No (${3 - failedAttempts})", Toast.LENGTH_SHORT).show() }
    }

    private fun handleExport(u: Uri) { try { val salt = getOrCreateSalt(); val enc = backupManager.createEncryptedBackup(currentSeed, salt, collaborators.toList(), masterPasswordHash); contentResolver.openOutputStream(u)?.use { it.write(enc.toByteArray()) }; Toast.makeText(this, "OK", Toast.LENGTH_SHORT).show() } catch (e: Exception) { Log.e(TAG, "Export failed") } }
    private fun handleImport(u: Uri) { try { val pkg = contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }; if (pkg != null && backupManager.restoreFromEncryptedBackup(pkg, currentSeed) != null) { finish(); startActivity(intent) } } catch (e: Exception) { Log.e(TAG, "Import failed") } }

    private fun loadPreferences() {
        val p = getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE); masterPasswordHash = p.getString("super_password_hash", null); failedAttempts = p.getInt("failed_attempts", 0); isAutoBackupEnabled = p.getBoolean("is_auto_backup_enabled", false); loadCollaborators()
        val s = p.getString("super_seed", null); if (s != null) currentSeed = s.split(" ") else { currentSeed = MnemonicManager.generateMnemonic(); p.edit().putString("super_seed", currentSeed.joinToString(" ")).apply() }
    }

    private fun getOrCreateSalt(): ByteArray {
        val p = getSharedPreferences("super_secure_prefs", Context.MODE_PRIVATE); val sEnc = p.getString("install_salt_enc", null)
        return if (sEnc != null) { try { Base64.getDecoder().decode(E2EManager.decryptWithHardwareKey(sEnc)) } catch (e: Exception) { generateAndSaveSalt(p) } } else generateAndSaveSalt(p)
    }

    private fun generateAndSaveSalt(p: android.content.SharedPreferences): ByteArray {
        val s = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val enc = E2EManager.encryptWithHardwareKey(Base64.getEncoder().encodeToString(s))
        p.edit().putString("install_salt_enc", enc).apply()
        return s
    }

    private fun performWipe() { getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE).edit().clear().apply(); try { E2EManager.deleteMasterKey() } catch (e: Exception) { }; finish() }

    @OptIn(ExperimentalMaterial3Api::class) @Composable private fun SuperDrawer(ds: DrawerState, sc: CoroutineScope) { ModalDrawerSheet { Spacer(Modifier.height(12.dp)); Text("SUPER MASTER CORE", Modifier.padding(16.dp), Color.Yellow, fontWeight = FontWeight.Bold); NavigationDrawerItem({ Text("HOME") }, currentScreen == SuperScreen.Home, { currentScreen = SuperScreen.Home; sc.launch { ds.close() } }, icon = { Icon(Icons.Default.Search, null) }); NavigationDrawerItem({ Text("RUBRICA") }, currentScreen == SuperScreen.Directory, { currentScreen = SuperScreen.Directory; sc.launch { ds.close() } }, icon = { Icon(Icons.Default.Group, null) }); NavigationDrawerItem({ Text("BACKUP") }, currentScreen == SuperScreen.SeedBackup, { currentScreen = SuperScreen.SeedBackup; sc.launch { ds.close() } }, icon = { Icon(Icons.Default.Backup, null) }); Spacer(Modifier.weight(1f)); TextButton({ performWipe() }, Modifier.fillMaxWidth()) { Text("RESET", color = Color.Red) } } }
    @OptIn(ExperimentalMaterial3Api::class) @Composable private fun SuperTopBar(ds: DrawerState, sc: CoroutineScope) { CenterAlignedTopAppBar(title = { Text("SUPER MASTER", color = Color.Yellow) }, navigationIcon = { IconButton({ sc.launch { ds.open() } }) { Icon(Icons.Default.Menu, null, tint = Color.Yellow) } }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)) }

    @Composable fun SuperAuthScreen() {
        var p by remember { mutableStateOf("") }; var c by remember { mutableStateOf("") }; var v by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Stars, null, tint = Color.Yellow, modifier = Modifier.size(64.dp))
                OutlinedTextField(p, { p = it }, label = { Text("Super Password") }, visualTransformation = if (v) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton({ v = !v }) { Icon(if (v) Icons.Default.Visibility else Icons.Default.VisibilityOff, null, tint = Color.Yellow) } })
                if (masterPasswordHash == null) OutlinedTextField(c, { c = it }, label = { Text("Conferma") }, visualTransformation = if (v) VisualTransformation.None else PasswordVisualTransformation())
                Button({ handleAuth(p, c) }, Modifier.fillMaxWidth().padding(top = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow)) { Text("ENTRA", color = Color.Black) }
            }
        }
    }

    @Composable fun DirectoryScreen() {
        var show by remember { mutableStateOf(false) }; var nN by remember { mutableStateOf("") }; var nO by remember { mutableStateOf(selectedMasterId) }
        LaunchedEffect(selectedMasterId) { nO = selectedMasterId }
        Scaffold(floatingActionButton = { FloatingActionButton({ show = true }, containerColor = Color.Yellow) { Icon(Icons.Default.Add, null) } }) { p ->
            Column(Modifier.fillMaxSize().background(Color.Black).padding(p).padding(16.dp)) {
                if (collaborators.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Vuota", color = Color.Gray) }
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) { items(collaborators) { m -> MasterItem(m) } }
            }
        }
        if (show) AlertDialog({ show = false }, { Button({ if (nN.isNotBlank() && nO.isNotBlank()) { addCollaborator(nN, nO); show = false } }) { Text("OK") } }, title = { Text("Add Master") }, text = { Column { OutlinedTextField(nN, { nN = it }, label = { Text("User") }); OutlinedTextField(nO, { nO = it }, label = { Text("Onion") }, trailingIcon = { IconButton({ isScanningForDirectory = true; cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) { Icon(Icons.Default.QrCodeScanner, null) } }) } })
    }

    @Composable fun MasterItem(m: MasterCollaborator) { Card({ currentScreen = SuperScreen.Details(m.onionAddress) }, Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, Color.Yellow.copy(alpha = 0.3f))) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Person, null, tint = Color.Yellow); Spacer(Modifier.width(16.dp)); Column { Text(m.username, fontWeight = FontWeight.Bold); Text(m.onionAddress.take(20), color = Color.Gray, fontSize = 10.sp) } } } }
    @Composable fun SuperHomeScreen() {
        var sID by remember { mutableStateOf(selectedMasterId) }; val f = collaborators.find { it.onionAddress == sID.trim() }
        LaunchedEffect(selectedMasterId) { sID = selectedMasterId }
        Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp)) {
            OutlinedTextField(sID, { sID = it }, label = { Text("ID Master") }, trailingIcon = { IconButton({ isScanningForDirectory = false; cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) { Icon(Icons.Default.QrCodeScanner, null) } })
            Spacer(Modifier.height(32.dp))
            if (f != null) MasterItem(f) else if (sID.isNotBlank()) Button({ addCollaborator("Master Ignoto", sID.trim()) }, Modifier.fillMaxWidth()) { Text("AGGIUNGI") }
        }
    }

    @Composable fun MasterDetailsScreen(onion: String) {
        val c = collaborators.find { it.onionAddress == onion } ?: return; var showR by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { IconButton({ currentScreen = SuperScreen.Home }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.Yellow) }; Text("DETTAGLI", color = Color.Yellow) }
            Button({ showR = true }, Modifier.fillMaxWidth().height(64.dp).padding(top = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow)) { Text("RICARICA ORA", color = Color.Black) }
            Spacer(Modifier.height(24.dp)); Text("Username: ${c.username}", color = Color.White); Text("ID: ${c.onionAddress}", color = Color.Gray, fontSize = 10.sp)
        }
        if (showR) RechargeDialog(onion, { showR = false }, { d, _ -> val upd = c.copy(rechargeHistory = c.rechargeHistory + RechargeEvent(System.currentTimeMillis(), d)); updateCollaborator(upd) })
    }

    @Composable fun RechargeDialog(onion: String, onD: () -> Unit, onG: (Int, String) -> Unit) {
        var d by remember { mutableIntStateOf(30) }; var code by remember { mutableStateOf<String?>(null) }
        AlertDialog(onD, { Button({ if (code == null) CoroutineScope(Dispatchers.IO).launch { val nt = timeFetcher.fetchTimeViaTor() ?: System.currentTimeMillis(); val c = totpManager.generateMasterCode(onion, nt, d); withContext(Dispatchers.Main) { code = c; onG(d, c) } } else onD() }) { Text(if (code == null) "GENERA" else "CHIUDI") } }, title = { Text("RICARICA") }, text = { if (code == null) Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { IconButton({ d -= 30 }) { Icon(Icons.Default.Remove, null) }; Text("$d GG"); IconButton({ d += 30 }) { Icon(Icons.Default.Add, null) } } else Text(code!!, style = MaterialTheme.typography.displayMedium, color = Color.Yellow) })
    }

    @Composable fun SuperSeedScreen() {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SEED IMPERIALE", color = Color.Magenta)
            Card(Modifier.padding(16.dp)) { Text(currentSeed.joinToString(" "), Modifier.padding(16.dp)) }
            if (currentSeed.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Auto Backup", color = Color.White)
                    Switch(isAutoBackupEnabled, { isAutoBackupEnabled = it; saveAutoBackupPreference(it) })
                }
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) { Button({ exportBackupLauncher.launch("super_backup.json") }) { Text("EXPORT") }; Button({ importBackupLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("IMPORT") } }
            OutlinedButton({ currentScreen = SuperScreen.Directory }, Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("SALTA") }
        }
    }

    private fun loadCollaborators() { val j = getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE).getString("master_list", null); if (j != null) { collaborators.clear(); collaborators.addAll(gson.fromJson(j, object : TypeToken<List<MasterCollaborator>>() {}.type)) } }
    private fun saveCollaborators() { getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE).edit().putString("master_list", gson.toJson(collaborators.toList())).apply() }
    private fun addCollaborator(n: String, o: String) { if (collaborators.none { it.onionAddress == o }) { collaborators.add(MasterCollaborator(n, o)); saveCollaborators() } }
    private fun updateCollaborator(u: MasterCollaborator) { val i = collaborators.indexOfFirst { it.onionAddress == u.onionAddress }; if (i != -1) { collaborators[i] = u; saveCollaborators() } }
    private fun saveAutoBackupPreference(e: Boolean) { getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE).edit().putBoolean("is_auto_backup_enabled", e).apply() }
    override fun onStop() { super.onStop(); autoBackup() }
    private fun autoBackup() { if (isAutoBackupEnabled && currentSeed.isNotEmpty()) getSharedPreferences("supermaster_prefs", Context.MODE_PRIVATE).getString("last_backup_uri", null)?.let { try { contentResolver.openOutputStream(it.toUri())?.use { os -> os.write(backupManager.createEncryptedBackup(currentSeed, getOrCreateSalt(), collaborators.toList(), masterPasswordHash).toByteArray()) } } catch (e: Exception) { } } }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class) @Composable fun ImperialScannerUI(onS: (String) -> Unit, onB: () -> Unit) { val ctx = LocalContext.current; val lo = LocalLifecycleOwner.current; val cpf = remember { ProcessCameraProvider.getInstance(ctx) }; var hs by remember { mutableStateOf(false) }; Box(Modifier.fillMaxSize()) { AndroidView({ c -> val pv = PreviewView(c); cpf.addListener({ val cp = cpf.get(); val ia = ImageAnalysis.Builder().build(); ia.setAnalyzer(Executors.newSingleThreadExecutor()) { ip -> val mi = ip.image; if (mi != null && !hs) { BarcodeScanning.getClient().process(InputImage.fromMediaImage(mi, ip.imageInfo.rotationDegrees)).addOnSuccessListener { for (b in it) b.rawValue?.let { if (!hs) { hs = true; onS(it) } } }.addOnCompleteListener { ip.close() } } else ip.close() }; try { cp.unbindAll(); cp.bindToLifecycle(lo, CameraSelector.DEFAULT_BACK_CAMERA, Preview.Builder().build().apply { setSurfaceProvider(pv.surfaceProvider) }, ia) } catch (e: Exception) {} }, ContextCompat.getMainExecutor(c)); pv }, Modifier.fillMaxSize()); IconButton(onB, Modifier.align(Alignment.TopStart).padding(16.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) } } }
