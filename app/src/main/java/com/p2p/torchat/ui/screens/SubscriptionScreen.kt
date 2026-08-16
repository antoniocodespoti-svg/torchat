package com.p2p.torchat.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
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
import com.p2p.torchat.ui.theme.NeonCyan
import com.p2p.torchat.ui.theme.RedAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    onionAddress: String,
    onActivate: (String) -> Unit,
) {
    var codeInput by remember { mutableStateOf("") }
    var showOnionQr by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    val sessionID = "057cd23bcf2b2a31467b8edcdf6737249476b7ba21d583fe91ff4464a94d1ef471"
    val supportEmail = "torp2pchat@proton.me"

    val sessionQr =
        remember {
            generateQRCodeBitmap(sessionID, 400, 400)
        }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Icon(Icons.Default.Timer, contentDescription = null, tint = RedAccent, modifier = Modifier.size(64.dp))

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "LICENZA SCADUTA",
                style = MaterialTheme.typography.headlineSmall,
                color = RedAccent,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "per supporto tecnico info e problemi ci potete contattare a questi indirizzi",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // User's Onion Address Box
            Surface(
                color = Color.DarkGray.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Il tuo indirizzo ID (.onion):", style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = onionAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(onionAddress)) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { showOnionQr = true }) {
                            Icon(Icons.Default.QrCode, contentDescription = "Mostra QR", tint = NeonCyan, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            if (showOnionQr) {
                AlertDialog(
                    onDismissRequest = { showOnionQr = false },
                    confirmButton = {
                        TextButton(onClick = { showOnionQr = false }) {
                            Text("CHIUDI", color = NeonCyan)
                        }
                    },
                    title = { Text("IL TUO ID ONION", color = NeonCyan) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val onionQr =
                                remember(onionAddress) {
                                    generateQRCodeBitmap(onionAddress, 512, 512)
                                }
                            onionQr?.let {
                                Card(
                                    border = androidx.compose.foundation.BorderStroke(2.dp, NeonCyan),
                                    modifier = Modifier.size(250.dp),
                                ) {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = "Onion QR",
                                        modifier = Modifier.fillMaxSize().background(Color.White).padding(16.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = onionAddress,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = Color.Gray,
                            )
                        }
                    },
                    containerColor = Color.Black,
                    shape = RoundedCornerShape(16.dp),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // OTP Input
            OutlinedTextField(
                value = codeInput,
                onValueChange = { if (it.length <= 6) codeInput = it },
                label = { Text("Codice Rinnovo (6 cifre)", color = NeonCyan) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { if (codeInput.length == 6) onActivate(codeInput) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            ) {
                Text("VERIFICA E RINNOVA", color = Color.Black, fontWeight = FontWeight.ExtraBold)
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Support Contacts Section
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(24.dp))

            Text("SUPPORTO AMMINISTRATORE", style = MaterialTheme.typography.labelLarge, color = Color.Gray)

            Spacer(modifier = Modifier.height(16.dp))

            Text("Email: $supportEmail", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Text("Session: ${sessionID.take(20)}...", color = Color.White, style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(16.dp))

            // Session QR Code
            sessionQr?.let {
                Card(
                    border = androidx.compose.foundation.BorderStroke(2.dp, NeonCyan),
                    modifier = Modifier.size(150.dp),
                ) {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Session QR",
                        modifier = Modifier.fillMaxSize().background(Color.White).padding(8.dp),
                    )
                }
                Text(
                    "Inquadra per ID Session",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
