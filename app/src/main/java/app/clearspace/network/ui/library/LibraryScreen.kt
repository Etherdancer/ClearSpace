package app.clearspace.network.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.clearspace.network.ui.components.ContentCard

import app.clearspace.network.ui.components.ContentDetailOverlay

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onNavigateToMediaVault: () -> Unit,
    onNavigateToGraph: () -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    val libraryContent by viewModel.libraryContent.collectAsState()
    val viewingContent by viewModel.viewingContent.collectAsState()
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.loadContent(searchQuery)
    }

    if (viewingContent != null) {
        ContentDetailOverlay(
            content = viewingContent!!,
            onClose = { viewModel.clearViewingContent() }
        )
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    androidx.compose.material3.ElevatedButton(onClick = onNavigateToMediaVault) {
                        Text("Media Vault")
                    }
                    androidx.compose.material3.ElevatedButton(onClick = onNavigateToGraph) {
                        Text("Graph View")
                    }
                }
            }
            
            item {
                app.clearspace.network.ui.components.InfoCard(
                    title = "Your Private Vault",
                    description = "This is your vault. 'Private' content is isolated and never leaves your device. 'Trusted' content syncs securely over the internet only with trusted users. 'Public' content is broadcast to nearby devices AND shared securely over the internet with your Remote Trusted Users.",
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { 
                        searchQuery = it
                        viewModel.loadContent(it)
                    },
                    label = { Text("Smart Scan") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)
                )
            }

            if (libraryContent.isEmpty()) {
                item {
                    Text("No private or trusted content found.", modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                val allTags = libraryContent
                    .filter { it.visibility == app.clearspace.network.storage.db.entity.Visibility.PRIVATE }
                    .flatMap { extractTags(it.body) }
                    .toSet()
                    .toList()
                    .sorted()
                
                if (allTags.isNotEmpty()) {
                    item {
                        Text("Tags", style = MaterialTheme.typography.titleSmall)
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            allTags.forEach { tag ->
                                androidx.compose.material3.FilterChip(
                                    selected = selectedTag == tag,
                                    onClick = { 
                                        selectedTag = if (selectedTag == tag) null else tag
                                    },
                                    label = { Text("#$tag") }
                                )
                            }
                        }
                    }
                }

                val filteredContent = if (selectedTag != null) {
                    libraryContent.filter { it.body.contains("#$selectedTag", ignoreCase = true) }
                } else {
                    libraryContent
                }

                items(filteredContent) { unit ->
                    Box(modifier = Modifier.clickable { viewModel.openContent(unit) }) {
                        ContentCard(unit)
                    }
                }
            }
        }
    }
}

private fun extractTags(text: String): List<String> {
    val regex = Regex("#([a-zA-Z0-9_]+)")
    return regex.findAll(text).map { it.groupValues[1] }.toList()
}
