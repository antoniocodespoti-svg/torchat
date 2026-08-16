package com.p2p.torchat.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.p2p.torchat.crypto.E2EManager
import com.p2p.torchat.model.Peer
import com.p2p.torchat.ui.theme.GreenAccent
import com.p2p.torchat.ui.theme.NeonCyan
import android.graphics.Color as AndroidColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationScreen(
    peer: Peer,
    onVerify: () -> Unit,
    onBack: () -> Unit,
) {
    val fingerprint =
        remember(peer.handshakePublicKey) {
            try {
                val pubKey = E2EManager.stringToPublicKey(peer.handshakePublicKey)
                E2EManager.getFingerprint(pubKey)
            } catch (e: Exception) {
                "ERRORE CHIAVE"
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VERIFICA IDENTITÀ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
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
                    .background(Color.Black)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (peer.isVerified) Icons.Default.VerifiedUser else Icons.Default.Warning,
                contentDescription = null,
                tint = if (peer.isVerified) GreenAccent else Color.Yellow,
                modifier = Modifier.size(64.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = peer.alias,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = if (peer.isVerified) "CONTATTO VERIFICATO" else "IDENTITÀ DA VERIFICARE",
                color = if (peer.isVerified) GreenAccent else Color.Yellow,
                style = MaterialTheme.typography.labelLarge,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Per essere sicuro di parlare con la persona giusta, confronta questo codice o scansiona il QR code qui sotto.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Fingerprint Box
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.3f)),
            ) {
                Text(
                    text = fingerprint,
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NeonCyan,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // QR Code
            val qrBitmap = remember(fingerprint) { generateQR(fingerprint, 400, 400) }
            qrBitmap?.let {
                Card(
                    modifier = Modifier.size(220.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, if (peer.isVerified) GreenAccent else NeonCyan),
                ) {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Verification QR",
                        modifier = Modifier.fillMaxSize().background(Color.White).padding(12.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            if (!peer.isVerified) {
                Button(
                    onClick = onVerify,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenAccent),
                ) {
                    Text("MARCA COME VERIFICATO", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text("OK, TORNA INDIETRO", color = Color.White)
                }
            }
        }
    }
}

private fun generateQR(
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
        null
    }
}
