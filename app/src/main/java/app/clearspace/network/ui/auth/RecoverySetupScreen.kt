package app.clearspace.network.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.clearspace.network.security.GatekeeperRepository
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.FileOutputStream
import app.clearspace.network.crypto.KeyManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverySetupScreen(
    dbKey: ByteArray,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { GatekeeperRepository(context, KeyManager(context)) }
    
    val scrollState = rememberScrollState()

    var sqEnabled by remember { mutableStateOf(false) }
    var ekEnabled by remember { mutableStateOf(false) }

    // Security Questions
    var q1 by remember { mutableStateOf("") }
    var q2 by remember { mutableStateOf("") }
    var q3 by remember { mutableStateOf("") }

    val fileCreator = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val keyManager = KeyManager(context)
                    val emergencyKey = ByteArray(32)
                    java.security.SecureRandom().nextBytes(emergencyKey)
                    
                    outputStream.write(emergencyKey)
                    repository.setupEmergencyKey(emergencyKey, dbKey)
                    ekEnabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Recovery Setup (Optional)") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Clear Space has NO central servers. If you forget your Master Password, we cannot recover it for you. Your data will be permanently locked.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge
            )
            
            Text("We highly recommend setting up at least one recovery method below.")

            // 1. Biometrics
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("1. Biometric Unlock", style = MaterialTheme.typography.titleMedium)
                    Text("Use your fingerprint or face to unlock the app.", style = MaterialTheme.typography.bodySmall)
                    Text("You can enable Biometric Unlock from the main Lock Screen or Settings.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }

            // 2. Security Questions
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("2. Security Questions", style = MaterialTheme.typography.titleMedium)
                    Text("Answer 3 personal questions. Answers are securely hashed.", style = MaterialTheme.typography.bodySmall)
                    
                    if (sqEnabled) {
                        Text("Enabled", color = MaterialTheme.colorScheme.primary)
                    } else {
                        OutlinedTextField(
                            value = q1,
                            onValueChange = { q1 = it },
                            label = { Text("What was the name of your first pet?") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = q2,
                            onValueChange = { q2 = it },
                            label = { Text("In what city were you born?") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = q3,
                            onValueChange = { q3 = it },
                            label = { Text("What is your mother's maiden name?") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                if (q1.isNotBlank() && q2.isNotBlank() && q3.isNotBlank()) {
                                    repository.setupSecurityQuestions(listOf(q1.trim(), q2.trim(), q3.trim()), dbKey)
                                    sqEnabled = true
                                }
                            },
                            enabled = q1.isNotBlank() && q2.isNotBlank() && q3.isNotBlank()
                        ) {
                            Text("Save Answers")
                        }
                    }
                }
            }

            // 3. Emergency Key File
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("3. Emergency Key File", style = MaterialTheme.typography.titleMedium)
                    Text("Save a cryptographically secure key file to your device. You can transfer this to a USB drive for ultimate security.", style = MaterialTheme.typography.bodySmall)
                    
                    if (ekEnabled) {
                        Text("Enabled", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = {
                            fileCreator.launch("clearspace_recovery.key")
                        }) {
                            Text("Generate Key File")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onComplete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Finish Setup")
            }
        }
    }
}
