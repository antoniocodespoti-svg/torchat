package com.p2p.torchat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.p2p.torchat.ui.theme.NeonCyan

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
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IMPOSTAZIONI", color = NeonCyan) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = NeonCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Button(onClick = onExportBackup, modifier = Modifier.fillMaxWidth()) { Text("Esporta Backup (JSON)") }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onImportBackup, modifier = Modifier.fillMaxWidth()) { Text("Importa Backup") }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Backup Automatico", color = Color.White, modifier = Modifier.weight(1f))
                Switch(checked = isAutoBackupEnabled, onCheckedChange = onToggleAutoBackup)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onChangePassword, modifier = Modifier.fillMaxWidth()) { Text("Cambia Password") }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onExtendLicense, modifier = Modifier.fillMaxWidth()) { Text("Attiva Licenza") }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onOpenInfo) { Text("Informazioni App", color = NeonCyan) }
            TextButton(onClick = onOpenTerms) { Text("Termini di Utilizzo", color = NeonCyan) }
        }
    }
}
