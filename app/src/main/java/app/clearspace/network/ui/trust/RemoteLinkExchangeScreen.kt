package app.clearspace.network.ui.trust

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import app.clearspace.network.crypto.KeyManager
import app.clearspace.network.ui.utilities.QrCodeGenerator
import app.clearspace.network.network.WebRtcManager
import org.webrtc.PeerConnection

@Composable
fun RemoteLinkExchangeScreen(modifier: Modifier = Modifier, onBack: () -> Unit, onNavigateToScanner: () -> Unit) {
    val context = LocalContext.current
    val keyManager = remember { KeyManager(context) }
    val webRtcManager = remember { WebRtcManager.getInstance(context) }
    val connectionState by webRtcManager.connectionState.collectAsState()
    
    // Generate a one-time rendezvous token
    val rendezvousToken = remember { java.util.UUID.randomUUID().toString() }
    val pubKeyFingerprint = remember { 
        val pubKey = keyManager.getOrGenerateIdentity().second
        android.util.Base64.encodeToString(pubKey, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    }
    
    val sigBase64 = remember {
        val sig = keyManager.signData(rendezvousToken.toByteArray(Charsets.UTF_8))
        android.util.Base64.encodeToString(sig, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    }
    
    val shareLink = "ClearSpace://trust?pk=$pubKeyFingerprint&rt=$rendezvousToken&sig=$sigBase64"
    val qrBitmap = remember(shareLink) { QrCodeGenerator.generate(shareLink) }

    LaunchedEffect(rendezvousToken) {
        webRtcManager.startCall(roomId = rendezvousToken, isCaller = true)
    }

    DisposableEffect(Unit) {
        onDispose {
            webRtcManager.close()
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Trust Verification", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "WARNING: Remote link exchange is vulnerable to Man-In-The-Middle attacks if the channel you use to send the link is compromised. Send this link only through secure, end-to-end encrypted messaging.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Step 1: Scan or Send Link", style = MaterialTheme.typography.titleMedium)
        Text("Send this link to the person you want to trust. They must open it in ClearSpace.", modifier = Modifier.padding(top = 4.dp))
        
        OutlinedTextField(
            value = shareLink,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        
        Button(
            onClick = {
                val sendIntent: Intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, shareLink)
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, null)
                context.startActivity(shareIntent)
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Share Link")
        }
        
        if (qrBitmap != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "QR Code",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(32.dp),
                contentScale = ContentScale.Fit
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text("Step 2: Wait for Connection", style = MaterialTheme.typography.titleMedium)
        Text("Once they open your link, ClearSpace will establish a secure connection using WebRTC to complete the handshake.", modifier = Modifier.padding(top = 4.dp))
        
        Text("Status: ${connectionState.name}", modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)

        if (connectionState != PeerConnection.IceConnectionState.CONNECTED && connectionState != PeerConnection.IceConnectionState.COMPLETED) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        } else {
            Text("Connection Established! Verifying Trust...", color = MaterialTheme.colorScheme.primary)
            LaunchedEffect(Unit) {
                app.clearspace.network.storage.sync.ClearSyncManager.getInstance(context).triggerSyncHandshake()
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(onClick = onNavigateToScanner, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text("Open Camera to Scan")
        }
        
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

