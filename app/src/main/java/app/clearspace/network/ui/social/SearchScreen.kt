package app.clearspace.network.ui.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import app.clearspace.network.discovery.GlobalDiscoveryAgent
import app.clearspace.network.discovery.GlobalProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(modifier: Modifier = Modifier, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val discoveryAgent = remember { GlobalDiscoveryAgent(context) }
    
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GlobalProfile>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    val incomingPings by discoveryAgent.incomingPings.collectAsState()

    LaunchedEffect(Unit) {
        discoveryAgent.startListeningForPings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Global Search") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(
                "Search for peers globally by their Alias. Only users who have explicitly opted into global discoverability will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Enter Exact Alias") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (searchQuery.isNotBlank()) {
                            isSearching = true
                            coroutineScope.launch {
                                searchResults = discoveryAgent.searchByAlias(searchQuery)
                                isSearching = false
                            }
                        }
                    },
                    enabled = !isSearching
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else if (searchResults.isEmpty() && searchQuery.isNotBlank()) {
                Text("No users found with that alias.", modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (incomingPings.isNotEmpty()) {
                        item {
                            Text("Incoming Connection Requests", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        items(incomingPings) { ping ->
                            val senderAlias = ping["senderAlias"] as? String ?: "Unknown"
                            val senderId = ping["senderId"] as? String ?: ""
                            val docId = ping["docId"] as? String ?: ""
                            
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(senderAlias, style = MaterialTheme.typography.titleMedium)
                                        Text("Wants to connect", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { coroutineScope.launch { discoveryAgent.rejectPing(docId) } }) {
                                            Text("Decline", color = MaterialTheme.colorScheme.error)
                                        }
                                        Button(onClick = { coroutineScope.launch { discoveryAgent.acceptPing(docId, senderId, senderAlias) } }) {
                                            Text("Accept")
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                        item {
                            Text("Search Results", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    items(searchResults) { profile ->
                        GlobalProfileCard(profile = profile, onPing = {
                            coroutineScope.launch {
                                discoveryAgent.pingUser(profile.id)
                                // Show some feedback in a real app (snackbar)
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun GlobalProfileCard(profile: GlobalProfile, onPing: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dynamic Avatar generated from ID
            val colorHash = kotlin.math.abs(profile.id.hashCode())
            val avatarColor = Color(
                red = (colorHash and 0xFF) / 255f,
                green = ((colorHash shr 8) and 0xFF) / 255f,
                blue = ((colorHash shr 16) and 0xFF) / 255f,
                alpha = 1f
            )
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(avatarColor, shape = androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = profile.alias.take(1).uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.alias, style = MaterialTheme.typography.titleMedium)
                if (profile.bioSnippet.isNotBlank()) {
                    Text(profile.bioSnippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("ID: ${profile.id.take(8)}...", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onPing) {
                Text("Ping")
            }
        }
    }
}
