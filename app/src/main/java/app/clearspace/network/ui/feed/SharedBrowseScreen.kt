package app.clearspace.network.ui.feed

import android.net.Uri
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.clearspace.network.IContentRenderer
import app.clearspace.network.security.guard.ContentRendererService
import androidx.compose.ui.unit.dp
import app.clearspace.network.discovery.ContentHeader
import app.clearspace.network.storage.db.entity.ContentUnit
import java.text.SimpleDateFormat
import java.util.*
import app.clearspace.network.ui.feed.SharedBrowseViewModel.SourceFilter
import app.clearspace.network.ui.components.FlagConstants
import app.clearspace.network.ui.components.FlagConstants.FlagType
import app.clearspace.network.ui.components.ContentDetailOverlay
import app.clearspace.network.reputation.ReputationAgent

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SharedBrowseScreen(
    modifier: Modifier = Modifier, 
    viewModel: SharedBrowseViewModel,
    spaceId: String? = null,
    spaceName: String? = null,
    onBack: (() -> Unit)? = null,
    onCreateContent: () -> Unit = {},
    onShareToGroup: (String, String) -> Unit = { _, _ -> }
) {
    val headers by viewModel.sharedHeaders.collectAsState()
    val loading by viewModel.loadingHeader.collectAsState()
    val viewingContent by viewModel.viewingContent.collectAsState()

    if (viewingContent != null) {
        ContentDetailOverlay(
            content = viewingContent!!,
            onClose = { viewModel.clearViewingContent() },
            onSave = { visibility ->
                viewModel.saveToPrivateLibrary(viewingContent!!, visibility)
            },
            onVote = { isUpvote ->
                viewModel.vote(viewingContent!!, isUpvote)
            },
            onFlagVote = { flag, category ->
                viewModel.voteFlag(viewingContent!!, flag, category)
            },
            onShareToGroup = onShareToGroup,
            onDeepLinkContent = { hash ->
                viewModel.openContent("local:0", hash)
            },
            onReportContent = { reason ->
                viewingContent?.let { viewModel.reportContent(it, reason) }
            },
            onBlockAuthor = { authorId ->
                viewModel.blockAuthor(authorId)
            }
        )
    } else {
        Scaffold(
            modifier = modifier,
            topBar = { 
                TopAppBar(
                    title = { Text(spaceName ?: "Nearby Shared Content") },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    }
                ) 
            },
            floatingActionButton = {
                if (spaceId != null) {
                    androidx.compose.material3.FloatingActionButton(onClick = onCreateContent) {
                        Icon(Icons.Filled.Add, contentDescription = "Create Content")
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                val searchQuery by viewModel.searchQuery.collectAsState()
                val filteredHeaders = headers.filter { 
                    (it.second.title.contains(searchQuery, ignoreCase = true) ||
                    it.second.authorAlias.contains(searchQuery, ignoreCase = true) ||
                    it.second.categoryFlags.contains(searchQuery, ignoreCase = true)) &&
                    it.second.spaceId == spaceId
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        if (spaceId == null) {
                            app.clearspace.network.ui.components.InfoCard(
                                title = "Public Feed",
                                description = "This feed shows 'Public' content from nearby devices and your Remote Trusted Users.",
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            label = { Text("Smart Scan (Filter local headers)") },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                        
                        val currentFilter by viewModel.sourceFilter.collectAsState()
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = currentFilter == SourceFilter.ALL,
                                onClick = { viewModel.updateSourceFilter(SourceFilter.ALL) },
                                label = { Text("All") }
                            )
                            FilterChip(
                                selected = currentFilter == SourceFilter.ACQUAINTANCES,
                                onClick = { viewModel.updateSourceFilter(SourceFilter.ACQUAINTANCES) },
                                label = { Text("Acquaintances") }
                            )
                            FilterChip(
                                selected = currentFilter == SourceFilter.TRUSTED,
                                onClick = { viewModel.updateSourceFilter(SourceFilter.TRUSTED) },
                                label = { Text("Trusted") }
                            )
                        }
                    }

                    if (filteredHeaders.isEmpty()) {
                        item {
                            Text(
                                "No shared content matches your search, or sharing is not enabled on nearby devices.",
                                modifier = Modifier.padding(bottom = 16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        items(filteredHeaders) { (ipPort, header) ->
                            HeaderCard(ipPort = ipPort, header = header) {
                                viewModel.openContent(ipPort, header.contentHash)
                            }
                        }
                    }
                }

                if (loading) {
                    androidx.activity.compose.BackHandler {
                        viewModel.cancelLoading()
                    }
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
fun HeaderCard(ipPort: String, header: ContentHeader, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(header.title, style = MaterialTheme.typography.titleMedium)
            Text("By ${header.authorAlias} • From $ipPort", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            Text("Published: ${sdf.format(Date(header.timestamp))}", style = MaterialTheme.typography.bodySmall)
            Text("Reputation: ${header.reputationScore}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
