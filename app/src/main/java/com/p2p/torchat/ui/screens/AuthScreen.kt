package com.p2p.torchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.p2p.torchat.ui.theme.NeonCyan
import com.p2p.torchat.ui.theme.RedAccent

enum class AuthMode {
    CREATE,
    LOGIN,
    CHANGE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    mode: AuthMode,
    attemptsLeft: Int = 3,
    onAuthSuccess: (String) -> Boolean,
    onBack: (() -> Unit)? = null,
) {
    var oldPassword by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var onionAddress by remember { mutableStateOf("") }

    var oldPasswordVisible by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }

    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text("SICUREZZA", letterSpacing = 2.sp, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()) // Enable scrolling
                        .border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                        .padding(32.dp),
            ) {
                Icon(
                    imageVector =
                        when (mode) {
                            AuthMode.CREATE -> Icons.Default.Security
                            AuthMode.LOGIN -> Icons.Default.Lock
                            AuthMode.CHANGE -> Icons.Default.Security
                        },
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(64.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text =
                        when (mode) {
                            AuthMode.CREATE -> "CONFIGURA ACCESSO"
                            AuthMode.LOGIN -> "ACCESSO PROTETTO"
                            AuthMode.CHANGE -> "CAMBIA PASSWORD"
                        },
                    style = MaterialTheme.typography.headlineSmall,
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text =
                        when (mode) {
                            AuthMode.CREATE -> "Inserisci l'indirizzo .onion da Orbot e scegli una password."
                            AuthMode.LOGIN -> "Inserisci la tua chiave di accesso per continuare."
                            AuthMode.CHANGE -> "Inserisci la vecchia password e la nuova per aggiornare la sicurezza."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                Text(
                    text = if (mode == AuthMode.LOGIN) "Tentativi rimasti: $attemptsLeft" else "Conserva la password con cura.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (attemptsLeft == 1) RedAccent else Color.Gray,
                )

                Spacer(modifier = Modifier.height(32.dp))

                if (mode == AuthMode.CREATE) {
                    OutlinedTextField(
                        value = onionAddress,
                        onValueChange = { input ->
                            onionAddress = if (input.contains("|")) input.split("|")[0].trim() else input.trim()
                            error = null
                        },
                        label = { Text("Tuo Indirizzo .onion (da Orbot)", color = NeonCyan.copy(alpha = 0.7f)) },
                        placeholder = { Text("es. abcdef123... .onion", color = Color.DarkGray) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = NeonCyan.copy(alpha = 0.3f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                            ),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (mode == AuthMode.CHANGE) {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = {
                            oldPassword = it
                            error = null
                        },
                        label = { Text("Vecchia Password", color = NeonCyan.copy(alpha = 0.7f)) },
                        visualTransformation = if (oldPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            val image = if (oldPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                            IconButton(onClick = { oldPasswordVisible = !oldPasswordVisible }) {
                                Icon(imageVector = image, contentDescription = null, tint = NeonCyan.copy(alpha = 0.5f))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = NeonCyan.copy(alpha = 0.3f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                            ),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text(if (mode == AuthMode.CHANGE) "Nuova Password" else "Password", color = NeonCyan.copy(alpha = 0.7f)) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        val image = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(imageVector = image, contentDescription = null, tint = NeonCyan.copy(alpha = 0.5f))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = NeonCyan.copy(alpha = 0.3f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                )

                if (mode == AuthMode.CREATE || mode == AuthMode.CHANGE) {
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            error = null
                        },
                        label = {
                            Text(
                                if (mode == AuthMode.CHANGE) "Conferma Nuova Password" else "Conferma Password",
                                color = NeonCyan.copy(alpha = 0.7f),
                            )
                        },
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            val image = if (confirmPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(imageVector = image, contentDescription = null, tint = NeonCyan.copy(alpha = 0.5f))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = NeonCyan.copy(alpha = 0.3f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                            ),
                    )
                }

                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = it, color = RedAccent, style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        if (mode == AuthMode.CHANGE) {
                            if (oldPassword.isEmpty() || password.isEmpty()) {
                                error = "Compila tutti i campi."
                                return@Button
                            }
                            if (password != confirmPassword) {
                                error = "Le nuove password non coincidono."
                                return@Button
                            }
                            if (!onAuthSuccess("VERIFY:$oldPassword|$password")) {
                                error = "Vecchia password errata."
                            }
                        } else {
                            if (password.isEmpty()) {
                                error = "Inserisci una password."
                                return@Button
                            }
                            if (mode == AuthMode.CREATE && password != confirmPassword) {
                                error = "Le password non coincidono."
                                return@Button
                            }
                            if (mode == AuthMode.CREATE) {
                                if (onionAddress.isBlank()) {
                                    error = "Inserisci l'indirizzo .onion."
                                    return@Button
                                }
                                onAuthSuccess("CREATE:$password|$onionAddress")
                            } else {
                                if (!onAuthSuccess(password)) {
                                    error = "Password errata. Riprova."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text =
                            when (mode) {
                                AuthMode.CREATE -> "SALVA E ENTRA"
                                AuthMode.LOGIN -> "SBLOCCA"
                                AuthMode.CHANGE -> "AGGIORNA PASSWORD"
                            },
                        color = Color.Black,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }

                if (mode == AuthMode.LOGIN) {
                    var showWipeConfirm by remember { mutableStateOf(false) }

                    Spacer(modifier = Modifier.height(24.dp))
                    TextButton(
                        onClick = { showWipeConfirm = true },
                    ) {
                        Text(
                            "DISTRUGGI DATI (EMERGENZA)",
                            color = RedAccent.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    if (showWipeConfirm) {
                        AlertDialog(
                            onDismissRequest = { showWipeConfirm = false },
                            title = { Text("PROTOCOLLO WIPE", color = RedAccent) },
                            text = {
                                Text(
                                    "ATTENZIONE: Questa azione cancellerà istantaneamente tutti i dati sul telefono. I backup esterni non verranno toccati. Sei sicuro?",
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showWipeConfirm = false
                                        onAuthSuccess("WIPE_NOW")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RedAccent),
                                ) {
                                    Text("SÌ, CANCELLA TUTTO")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showWipeConfirm = false }) {
                                    Text("ANNULLA")
                                }
                            },
                            containerColor = Color.Black,
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }
        }
    }
}
