package app.clearspace.network.ui.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import app.clearspace.network.crypto.KeyManager
import app.clearspace.network.persona.PersonaAgent

@Composable
fun SocialScreen(modifier: Modifier = Modifier, onNavigateToGroups: () -> Unit = {}, onNavigateToTrust: () -> Unit = {}, viewModel: SocialViewModel = viewModel()) {
    val context = LocalContext.current
    val personaAgent = remember { PersonaAgent(context, KeyManager(context)) }
    var showProfile by remember { mutableStateOf(personaAgent.showProfileToNearbyUsers) }
    
    val profiles by viewModel.nearbyProfiles.collectAsState()
    
    val acquaintances = profiles.filter { it.isAcquaintance }
    val nearby = profiles.filter { !it.isAcquaintance }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Social Network", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.CenterVertically))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onNavigateToTrust) {
                        Text("Trust QR")
                    }
                    Button(onClick = onNavigateToGroups) {
                        Text("Groups")
                    }
                }
            }
            
            app.clearspace.network.ui.components.InfoCard(
                title = "Network & Identity",
                description = "Connect with nearby users or exchange Trust QRs to build your private network. Your profile is only visible if you enable it.",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show Profile", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                    Switch(
                        checked = showProfile,
                        onCheckedChange = { 
                            showProfile = it
                            personaAgent.showProfileToNearbyUsers = it 
                        }
                    )
                }
            }
        }
        
        if (acquaintances.isNotEmpty()) {
            item {
                Text("Acquaintances", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(acquaintances) { profile ->
                ProfileCard(
                    alias = profile.alias,
                    peerId = profile.peerId,
                    isAcquaintance = true,
                    onAdd = {}
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        item {
            Text("Nearby Profiles", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        }
        
        if (nearby.isEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    app.clearspace.network.ui.components.RadarAnimation(modifier = Modifier.size(150.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Searching for nearby users...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(nearby) { profile ->
                ProfileCard(
                    alias = profile.alias,
                    peerId = profile.peerId,
                    isAcquaintance = false,
                    onAdd = { viewModel.addAcquaintance(profile.peerId) }
                )
            }
        }
    }
}

@Composable
fun ProfileCard(alias: String, peerId: String, isAcquaintance: Boolean, onAdd: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(alias, style = MaterialTheme.typography.titleMedium)
                Text("ID: $peerId", style = MaterialTheme.typography.labelSmall)
            }
            if (!isAcquaintance) {
                Button(onClick = onAdd) {
                    Text("Add")
                }
            } else {
                Text("Acquaintance", color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
