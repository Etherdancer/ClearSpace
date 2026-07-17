package app.clearspace.network

import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.security.MessageDigest
import app.clearspace.network.crypto.KeyManager
import app.clearspace.network.security.EmulatorDetector
import app.clearspace.network.security.GatekeeperRepository
import app.clearspace.network.security.RootDetector
import app.clearspace.network.security.IntegrityChecker
import app.clearspace.network.storage.db.AetherDatabase
import app.clearspace.network.theme.ClearSpaceTheme
import app.clearspace.network.ui.auth.GatekeeperUiState
import app.clearspace.network.ui.auth.GatekeeperViewModel
import app.clearspace.network.ui.auth.LockScreen
import app.clearspace.network.ui.auth.OnboardingScreen
import app.clearspace.network.networking.SharingStateManager
import app.clearspace.network.networking.SharingToggleViewModel
import app.clearspace.network.ui.feed.SharedBrowseViewModel

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : FragmentActivity() {
  private lateinit var gatekeeperRepo: GatekeeperRepository
  private lateinit var gatekeeperViewModel: GatekeeperViewModel
  private lateinit var sharingStateManager: SharingStateManager
  private lateinit var sharingToggleViewModel: SharingToggleViewModel

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    enableEdgeToEdge()



    val emulatorDetector = EmulatorDetector(this)
    if (emulatorDetector.isEmulator()) {
      setContent {
        ClearSpaceTheme {
          Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
            Box(contentAlignment = Alignment.Center) {
              Text(
                  "This app can only be used on a physical device.",
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  style = MaterialTheme.typography.headlineMedium
              )
            }
          }
        }
      }
      return
    }

    val rootDetector = RootDetector()
    val isRootedOrCustom = rootDetector.isRootedOrCustomRom()
    val isIntegrityValid = IntegrityChecker.verifySignature(this)

    val keyManager = KeyManager(this)
    gatekeeperRepo = GatekeeperRepository(this, keyManager)
    gatekeeperViewModel = GatekeeperViewModel(gatekeeperRepo)
    
    sharingStateManager = SharingStateManager(this)
    sharingToggleViewModel = SharingToggleViewModel(sharingStateManager)

    setContent {
      val themeManager = androidx.compose.runtime.remember { app.clearspace.network.theme.ThemeManager(this@MainActivity) }
      val currentTheme by themeManager.theme.collectAsState()
      val uiState by gatekeeperViewModel.uiState.collectAsState()

      ClearSpaceTheme(appTheme = currentTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          when (val state = uiState) {
            is GatekeeperUiState.Loading -> { } // Show empty or splash
            is GatekeeperUiState.UpdateRequired -> {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Update Required",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                        Text(
                            "A mandatory security update is available. You must update the app to continue.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(16.dp))
                        Button(onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(state.downloadUrl))
                            this@MainActivity.startActivity(intent)
                        }) {
                            Text("Download Latest Version")
                        }
                    }
                }
            }
            is GatekeeperUiState.Onboarding -> OnboardingScreen(onComplete = {
                val chars = it.toCharArray()
                gatekeeperViewModel.completeOnboarding(chars)
                java.util.Arrays.fill(chars, '\u0000')
            })
            is GatekeeperUiState.RecoverySetup -> app.clearspace.network.ui.auth.RecoverySetupScreen(
                dbKey = state.dbKey,
                onComplete = { gatekeeperViewModel.completeRecoverySetup(state.dbKey) }
            )
            is GatekeeperUiState.Recovering -> app.clearspace.network.ui.auth.RecoveryScreen(
                onRecovered = { dbKey -> gatekeeperViewModel.completeRecovery(dbKey) },
                onBack = { gatekeeperViewModel.cancelRecovery() }
            )
            is GatekeeperUiState.PromptPassword, is GatekeeperUiState.PasswordError, is GatekeeperUiState.LockedOut -> {
              LockScreen(
                  uiState = state, 
                  onSubmitPassword = {
                      val chars = it.toCharArray()
                      gatekeeperViewModel.submitPassword(chars)
                      java.util.Arrays.fill(chars, '\u0000')
                  },
                  onBiometricClick = {
                      if (gatekeeperViewModel.canUseBiometric()) {
                          try {
                              val cipher = gatekeeperViewModel.getBiometricCipher(javax.crypto.Cipher.DECRYPT_MODE)
                              val helper = app.clearspace.network.security.BiometricHelper(this@MainActivity, keyManager)
                              helper.showBiometricPrompt(
                                  this@MainActivity,
                                  "Unlock Clear Space",
                                  "Confirm your identity to unlock",
                                  onSuccess = { gatekeeperViewModel.authenticateWithBiometric(cipher) },
                                  onError = { /* handle error */ }
                              )
                          } catch (e: Exception) {
                              // Handle crypto init error
                          }
                      }
                  },
                  canUseBiometric = gatekeeperViewModel.canUseBiometric(),
                  onEnrollBiometric = { pass ->
                      try {
                          val cipher = gatekeeperViewModel.getBiometricCipher(javax.crypto.Cipher.ENCRYPT_MODE)
                          val helper = app.clearspace.network.security.BiometricHelper(this@MainActivity, keyManager)
                          helper.showBiometricPrompt(
                              this@MainActivity,
                              "Enable Biometric Unlock",
                              "Confirm your identity to enable biometric unlock",
                              onSuccess = { 
                                  val passChars = pass.toCharArray()
                                  val passCharsCopy = pass.toCharArray()
                                  if (gatekeeperViewModel.enrollBiometric(cipher, passChars)) {
                                      gatekeeperViewModel.submitPassword(passCharsCopy)
                                  } 
                                  java.util.Arrays.fill(passChars, '\u0000')
                                  java.util.Arrays.fill(passCharsCopy, '\u0000')
                              },
                              onError = { }
                          )
                      } catch (e: Exception) {
                          // Handle error
                      }
                  },
                  onRecoverPassword = {
                      gatekeeperViewModel.startRecovery()
                  }
              )
            }
            is GatekeeperUiState.Authenticated, GatekeeperUiState.Active -> {
                if (state is GatekeeperUiState.Authenticated) {
                    // Initialize databases with the real key
                    val privateDb = AetherDatabase.getPrivateDatabase(this@MainActivity, state.dbKey)
                    val sharedDb = AetherDatabase.getSharedDatabase(this@MainActivity, state.dbKey)
                    gatekeeperViewModel.clearDbKey()
                    
                    // UGC Compliance: Sync blacklist from Firebase
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val moderationAgent = app.clearspace.network.moderation.ModerationAgent(this@MainActivity)
                        moderationAgent.syncBlacklist()
                        moderationAgent.syncTombstones()
                    }
                    
                    // Start ClearSync (WebRTC DataChannel Listener)
                    app.clearspace.network.storage.sync.ClearSyncManager.getInstance(this@MainActivity).startSyncService()
                }
                
                val sharedBrowseViewModel = SharedBrowseViewModel(this@MainActivity.application)
                val spaceViewModel = app.clearspace.network.ui.feed.SpaceViewModel(this@MainActivity.application)
                
                val deepLinkTitleRaw = if (intent?.action == android.content.Intent.ACTION_VIEW && intent?.data?.scheme == "ClearSpace") {
                    intent?.data?.getQueryParameter("title")
                } else null
                // Sanitise to prevent injection
                val deepLinkTitle = deepLinkTitleRaw?.replace(Regex("<[^>]*>"), "")?.take(100)

                androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                    if (isRootedOrCustom || !isIntegrityValid) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val warningText = if (!isIntegrityValid) {
                                "Security Warning: App integrity check failed. The app may have been modified."
                            } else {
                                "Security Warning: Root or Custom ROM detected. Security cannot be guaranteed."
                            }
                            Text(
                                warningText,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        MainNavigation(
                            sharingToggleViewModel = sharingToggleViewModel,
                            sharedBrowseViewModel = sharedBrowseViewModel,
                            spaceViewModel = spaceViewModel,
                            initialDeepLinkTitle = deepLinkTitle
                        )
                    }
                }
            }
          }
        }
      }
    }
  }
}

