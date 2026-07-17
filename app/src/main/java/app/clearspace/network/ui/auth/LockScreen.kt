package app.clearspace.network.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.clearspace.network.auth.SecureKeyboard

@Composable
fun LockScreen(
    uiState: GatekeeperUiState,
    onSubmitPassword: (String) -> Unit,
    onBiometricClick: () -> Unit = {},
    onEnrollBiometric: (String) -> Unit = {},
    canUseBiometric: Boolean = false,
    onRecoverPassword: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var password by remember { mutableStateOf("") }

    val isError = uiState is GatekeeperUiState.PasswordError
    val isLocked = uiState is GatekeeperUiState.LockedOut

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Clear Space",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter master password",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(32.dp))

                SecureKeyboard(
                    currentPassword = password,
                    onPasswordChange = { password = it },
                    onSubmit = {
                        if (!isLocked) {
                            onSubmitPassword(password)
                            password = ""
                        }
                    }
                )

                if (!isLocked) {
                    Spacer(modifier = Modifier.height(16.dp))
                    IconButton(
                        onClick = {
                            if (canUseBiometric) {
                                onBiometricClick()
                            } else if (password.isNotEmpty()) {
                                onEnrollBiometric(password)
                                password = ""
                            }
                        },
                        modifier = Modifier.size(64.dp)
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Lock,
                            contentDescription = if (canUseBiometric) "Unlock with Biometrics" else "Enable Biometric Unlock",
                            tint = if (canUseBiometric) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(visible = isError, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        text = "Incorrect password.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                AnimatedVisibility(visible = isLocked, enter = fadeIn(), exit = fadeOut()) {
                    if (uiState is GatekeeperUiState.LockedOut) {
                        Text(
                            text = "Too many failed attempts. Locked for ${uiState.remainingSeconds}s.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                TextButton(
                    onClick = onRecoverPassword,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Forgot Password? Recovery Options")
                }

                var showWipeConfirmDialog by remember { mutableStateOf(false) }

                TextButton(
                    onClick = { showWipeConfirmDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Lost Password? Wipe App Data & Reset")
                }

                if (showWipeConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showWipeConfirmDialog = false },
                        icon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        title = { Text("Wipe All Data?") },
                        text = { Text("This will permanently delete your encrypted database and all settings. This action cannot be undone.") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val keyManager = app.clearspace.network.crypto.KeyManager(context)
                                    keyManager.wipeAllKeys()
                                    
                                    val packageManager = context.packageManager
                                    val intent = packageManager.getLaunchIntentForPackage(context.packageName)
                                    val componentName = intent?.component
                                    val mainIntent = android.content.Intent.makeRestartActivityTask(componentName)
                                    context.startActivity(mainIntent)
                                    Runtime.getRuntime().exit(0)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Wipe & Reset")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showWipeConfirmDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}
