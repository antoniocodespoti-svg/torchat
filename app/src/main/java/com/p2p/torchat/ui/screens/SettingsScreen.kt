package com.p2p.torchat.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.p2p.torchat.ui.theme.GreenAccent
import com.p2p.torchat.ui.theme.NeonCyan
import com.p2p.torchat.ui.theme.NeonMagenta
import android.graphics.Color as AndroidColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    isAutoBackupEnabled: Boolean,
    onToggleAutoBackup: (Boolean) -> Unit,
    onChangePassword: () -> Unit,
    onExtendLicense: () -> Unit,
    expiryDate: Long,
    onOpenInfo: () -> Unit,
    onOpenTerms: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IMPOSTAZIONI", letterSpacing = 2.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()), // Enable scrolling for support section
        ) {
            Text(
                text = "APP",
                style = MaterialTheme.typography.labelLarge,
                color = NeonCyan,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text("Informazioni e Sicurezza", fontWeight = FontWeight.Bold) },
                        supportingContent = {
                            Text(
                                "Leggi il manifesto della tua privacy.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Info, null, tint = NeonCyan) },
                        modifier = Modifier.clickable { onOpenInfo() },
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.2f))
                    ListItem(
                        headlineContent = { Text("Termini d'Uso", fontWeight = FontWeight.Bold) },
                        supportingContent = {
                            Text(
                                "Avviso legale e condizioni di utilizzo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Gavel, null, tint = NeonCyan) },
                        modifier = Modifier.clickable { onOpenTerms() },
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "GESTIONE DATI",
                style = MaterialTheme.typography.labelLarge,
                color = NeonCyan,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Backup Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Backup e Ripristino",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Esporta la tua identità Tor e la tua lista amici per non perderli mai.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onExportBackup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ESPORTA BACKUP", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onImportBackup,
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonMagenta),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, tint = NeonMagenta)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("IMPORTA BACKUP", color = NeonMagenta, fontWeight = FontWeight.ExtraBold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Backup Automatico", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "Aggiorna il file JSON ogni volta che chiudi l'app.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                        Switch(
                            checked = isAutoBackupEnabled,
                            onCheckedChange = onToggleAutoBackup,
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "SICUREZZA",
                style = MaterialTheme.typography.labelLarge,
                color = NeonCyan,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    ListItem(
                        headlineContent = { Text("Password App", fontWeight = FontWeight.Bold) },
                        supportingContent = {
                            Text(
                                "La tua password protegge l'accesso fisico.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Security, null, tint = NeonCyan) },
                        modifier = Modifier.clickable { onChangePassword() },
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.2f))

                    val daysLeft = (expiryDate - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)
                    if (daysLeft <= 5) {
                        ListItem(
                            headlineContent = { Text("Estendi Licenza", fontWeight = FontWeight.Bold) },
                            supportingContent = {
                                Text(
                                    "Inserisci un codice per aggiungere giorni.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Timer, null, tint = NeonCyan) },
                            modifier = Modifier.clickable { onExtendLicense() },
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text("Abbonamento Attivo", fontWeight = FontWeight.Bold) },
                            supportingContent = {
                                Text(
                                    "Mancano $daysLeft giorni alla scadenza.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                )
                            },
                            leadingContent = { Icon(Icons.Default.CheckCircle, null, tint = GreenAccent) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "SUPPORTO AMMINISTRATORE",
                style = MaterialTheme.typography.labelLarge,
                color = NeonCyan,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val sessionID = "057cd23bcf2b2a31467b8edcdf6737249476b7ba21d583fe91ff4464a94d1ef471"
                val supportEmail = "torp2pchat@proton.me"
                val clipboardManager = LocalClipboardManager.current

                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "per supporto tecnico info e problemi ci potete contattare a questi indirizzi",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Email", style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                            Text(supportEmail, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(supportEmail)) }) {
                            Icon(Icons.Default.ContentCopy, null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Session ID", style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                            Text(sessionID.take(24) + "...", style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(sessionID)) }) {
                            Icon(Icons.Default.ContentCopy, null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val qrBitmap = remember { generateAdminQRCode(sessionID, 300, 300) }
                    qrBitmap?.let { bitmap ->
                        Card(
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
                            modifier = Modifier.size(120.dp),
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Session QR",
                                modifier = Modifier.fillMaxSize().background(Color.White).padding(8.dp),
                            )
                        }
                        Text(
                            "Scansiona ID Session",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun generateAdminQRCode(
    text: String,
    width: Int,
    height: Int,
): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
