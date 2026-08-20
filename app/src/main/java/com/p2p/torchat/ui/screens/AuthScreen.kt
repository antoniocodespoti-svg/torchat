package com.p2p.torchat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.p2p.torchat.model.AuthMode
import com.p2p.torchat.ui.theme.NeonCyan
import com.p2p.torchat.ui.theme.RedAccent
import com.p2p.torchat.util.Constants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    mode: AuthMode,
    attemptsLeft: Int = Constants.MAX_AUTH_ATTEMPTS,
    onAuthSuccess: (String) -> Boolean,
    onBack: (() -> Unit)? = null,
) {
    var oldPassword by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showWipeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (mode) {
                            AuthMode.CREATE -> "CONFIGURA SICUREZZA"
                            AuthMode.LOGIN -> "ACCESSO PROTETTO"
                            AuthMode.CHANGE -> "CAMBIA PASSWORD"
                        },
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, "Indietro", tint = NeonCyan)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = NeonCyan
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (mode == AuthMode.LOGIN) {
                Text(
                    "Tentativi rimasti: $attemptsLeft",
                    color = if (attemptsLeft <= 1) RedAccent else Color.Gray,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (mode == AuthMode.CHANGE) {
                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it },
                    label = { Text("Password Attuale") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (mode == AuthMode.CREATE) "Nuova Password" else "Password") },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                }
            )

            if (mode == AuthMode.CREATE || mode == AuthMode.CHANGE) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Conferma Password") },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = RedAccent, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    when (mode) {
                        AuthMode.CREATE -> {
                            if (password.length < 8) error = "Minimo 8 caratteri"
                            else if (password != confirmPassword) error = "Le password non coincidono"
                            else onAuthSuccess(password)
                        }
                        AuthMode.LOGIN -> {
                            if (!onAuthSuccess(password)) {
                                password = ""
                                error = "Password errata"
                            }
                        }
                        AuthMode.CHANGE -> {
                            if (password != confirmPassword) error = "Le nuove password non coincidono"
                            else if (password.length < 8) error = "Minimo 8 caratteri"
                            else onAuthSuccess("VERIFY:$oldPassword|$password")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
            ) {
                Text(if (mode == AuthMode.LOGIN) "SBLOCCA" else "CONFERMA")
            }

            if (mode == AuthMode.LOGIN) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = { showWipeDialog = true }) {
                    Text("Dimenticata? Esegui Wipe", color = RedAccent)
                }
            }
        }
    }

    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = { Text("Wipe Totale") },
            text = { Text("Questa azione cancellerà tutti i dati. Sei sicuro?") },
            confirmButton = {
                Button(onClick = { /* Handled in MainActivity via specialized logic */ }, colors = ButtonDefaults.buttonColors(containerColor = RedAccent)) {
                    Text("ELIMINA TUTTO")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDialog = false }) { Text("ANNULLA") }
            }
        )
    }
}
