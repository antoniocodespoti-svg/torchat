package com.p2p.torchat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2p.torchat.ui.theme.NeonCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsOfUseScreen(
    isViewOnly: Boolean = false,
    onAccept: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TERMINI DI UTILIZZO", color = NeonCyan) },
                navigationIcon = {
                    if (isViewOnly) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = NeonCyan)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Benvenuto su TorP2P Chat.", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Questa applicazione è progettata per la massima privacy. Non raccogliamo dati personali.", color = Color.Gray)
            // ... truncated for brevity, same content
            if (!isViewOnly) {
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("ACCETTA E CONTINUA") }
            }
        }
    }
}
