package app.clearspace.network.ui.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.clearspace.network.security.GatekeeperRepository
import java.io.InputStream
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.text.font.FontWeight
import app.clearspace.network.auth.SecureKeyboard

import app.clearspace.network.crypto.KeyManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(
    onRecovered: (ByteArray) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { GatekeeperRepository(context, KeyManager(context)) }
    
    val sqEnabled = remember { repository.isRecoverySqEnabled() }
    val ekEnabled = remember { repository.isRecoveryEkEnabled() }
    
    var q1 by remember { mutableStateOf("") }
    var q2 by remember { mutableStateOf("") }
    var q3 by remember { mutableStateOf("") }
    var sqError by remember { mutableStateOf(false) }

    var ekError by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    val recoveredKey = repository.recoverWithEmergencyKey(bytes)
                    if (recoveredKey != null) {
                        onRecovered(recoveredKey)
                    } else {
                        ekError = true
                    }
                }
            } catch (e: Exception) {
                ekError = true
                e.printStackTrace()
            }
        }
    }

    var showWipeConfirmDialog by remember { mutableStateOf(false) }
    var showResetPasswordDialog by remember { mutableStateOf<ByteArray?>(null) }
    
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf(false) }

    val passwordRegex = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[^\\w\\s]).{8,}$".toRegex()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Recovery") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            if (sqEnabled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recover with Security Questions", style = MaterialTheme.typography.titleMedium)
                        
                        OutlinedTextField(
                            value = q1,
                            onValueChange = { q1 = it; sqError = false },
                            label = { Text("What was the name of your first pet?") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = q2,
                            onValueChange = { q2 = it; sqError = false },
                            label = { Text("In what city were you born?") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = q3,
                            onValueChange = { q3 = it; sqError = false },
                            label = { Text("What is your mother's maiden name?") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        if (sqError) {
                            Text("Incorrect answers.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        Button(
                            onClick = {
                                val recoveredKey = repository.recoverWithSecurityQuestions(listOf(q1.trim(), q2.trim(), q3.trim()))
                                if (recoveredKey != null) {
                                    showResetPasswordDialog = recoveredKey
                                } else {
                                    sqError = true
                                }
                            },
                            enabled = q1.isNotBlank() && q2.isNotBlank() && q3.isNotBlank()
                        ) {
                            Text("Submit Answers")
                        }
                    }
                }
            }

            if (ekEnabled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recover with Emergency Key File", style = MaterialTheme.typography.titleMedium)
                        
                        if (ekError) {
                            Text("Invalid key file or incorrect format.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        Button(onClick = {
                            ekError = false
                            filePicker.launch(arrayOf("application/octet-stream"))
                        }) {
                            Text("Select Key File")
                        }
                    }
                }
            }

            if (!sqEnabled && !ekEnabled) {
                Text(
                    "You did not enable any recovery methods for this account.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            TextButton(
                onClick = { showWipeConfirmDialog = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Wipe App Data & Reset (Unrecoverable)")
            }
        }

        if (showWipeConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showWipeConfirmDialog = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("Wipe All Data?") },
                text = { Text("This will permanently delete your encrypted database and all settings. This action cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            val keyManager = app.clearspace.network.crypto.KeyManager(context)
                            keyManager.wipeAllKeys()
                            // Restart app to trigger onboarding
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

        if (showResetPasswordDialog != null) {
            AlertDialog(
                onDismissRequest = { /* Must reset password */ },
                title = { Text("Reset Master Password") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recovery successful. Please create a new master password.")
                        
                        SecureKeyboard(
                            currentPassword = newPassword,
                            onPasswordChange = { newPassword = it; passwordError = false },
                            onSubmit = { /* Ignore */ }
                        )

                        Text("Requirements: Min 8 chars, 1 uppercase, 1 lowercase, 1 number, 1 symbol.", style = MaterialTheme.typography.bodySmall)
                        
                        Text("Confirm Password:")
                        SecureKeyboard(
                            currentPassword = confirmPassword,
                            onPasswordChange = { confirmPassword = it; passwordError = false },
                            onSubmit = { /* Ignore */ }
                        )

                        if (passwordError) {
                            Text("Passwords do not match or do not meet requirements.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newPassword == confirmPassword && passwordRegex.matches(newPassword)) {
                                val dbKey = repository.resetPassword(newPassword.toCharArray(), showResetPasswordDialog!!)
                                onRecovered(dbKey)
                            } else {
                                passwordError = true
                            }
                        }
                    ) {
                        Text("Save New Password")
                    }
                }
            )
        }
    }
}
