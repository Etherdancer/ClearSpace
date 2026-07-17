package app.clearspace.network.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import app.clearspace.network.IContentRenderer
import app.clearspace.network.crypto.KeyManager
import app.clearspace.network.reputation.ReputationAgent
import app.clearspace.network.security.guard.ContentRendererService
import app.clearspace.network.storage.db.entity.ContentUnit
import app.clearspace.network.ui.components.FlagConstants.FlagType

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContentDetailOverlay(
    content: ContentUnit,
    onClose: () -> Unit,
    onSave: ((app.clearspace.network.storage.db.entity.Visibility) -> Unit)? = null,
    onVote: ((Boolean) -> Unit)? = null,
    onFlagVote: ((FlagType, String) -> Unit)? = null,
    onShareToGroup: ((String, String) -> Unit)? = null,
    onDeepLinkContent: ((String) -> Unit)? = null,
    onReportContent: ((String) -> Unit)? = null,
    onBlockAuthor: ((String) -> Unit)? = null
) {
    BackHandler(onBack = onClose)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(content.title) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val context = LocalContext.current
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More Options")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        if (onSave != null) {
                            DropdownMenuItem(
                                text = { Text("Save to Private Vault") },
                                onClick = {
                                    expanded = false
                                    onSave(app.clearspace.network.storage.db.entity.Visibility.PRIVATE)
                                    android.widget.Toast.makeText(context, "Saved to Vault", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Forward to Trusted Users") },
                                onClick = {
                                    expanded = false
                                    onSave(app.clearspace.network.storage.db.entity.Visibility.TRUSTED)
                                    android.widget.Toast.makeText(context, "Forwarding to Trusted Users...", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
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
                .verticalScroll(rememberScrollState())
        ) {
            Text("By ${content.authorAlias}", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(16.dp))
            
            val context = LocalContext.current
            var htmlContent by remember { mutableStateOf<String?>(null) }
            var showReportDialog by remember { mutableStateOf(false) }
            var selectedReason by remember { mutableStateOf("Child Safety / CSAM / CSAE") }
            
            DisposableEffect(content.body) {
                var binder: IContentRenderer? = null
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        binder = IContentRenderer.Stub.asInterface(service)
                        try {
                            htmlContent = binder?.renderMarkdownToHtml(content.body) ?: "Renderer error"
                        } catch (e: Exception) {
                            htmlContent = "Error: ${e.message}"
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        binder = null
                    }
                }
                
                val intent = Intent(context, ContentRendererService::class.java)
                val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!bound) {
                    htmlContent = content.body.replace("\n", "<br>") // Fallback to raw text
                }
                
                onDispose {
                    if (bound) {
                        try {
                            context.unbindService(connection)
                        } catch (e: Exception) {}
                    }
                }
            }
            
            if (content.videoPath != null) {
                Spacer(modifier = Modifier.height(16.dp))
                
                var isFullScreen by remember { mutableStateOf(false) }
                
                val exoPlayer = remember {
                    androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                        setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.parse(content.videoPath)))
                        prepare()
                    }
                }
                
                DisposableEffect(Unit) {
                    onDispose {
                        exoPlayer.release()
                    }
                }

                if (isFullScreen) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { isFullScreen = false },
                        properties = androidx.compose.ui.window.DialogProperties(
                            usePlatformDefaultWidth = false,
                            dismissOnBackPress = true,
                            decorFitsSystemWindows = false
                        )
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            AndroidView(
                                factory = { ctx ->
                                    androidx.media3.ui.PlayerView(ctx).apply {
                                        player = exoPlayer
                                        useController = true
                                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                },
                                update = { view ->
                                    if (view.player != exoPlayer) view.player = exoPlayer
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = { isFullScreen = false },
                                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                            ) {
                                Icon(Icons.Default.FullscreenExit, contentDescription = "Exit Fullscreen", tint = Color.White)
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                    AndroidView(
                        factory = { ctx ->
                            androidx.media3.ui.PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true
                                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        update = { view ->
                            if (view.player != exoPlayer && !isFullScreen) {
                                view.player = exoPlayer
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = { isFullScreen = true },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    ) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Enter Fullscreen", tint = Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else if (!content.thumbnailBase64.isNullOrEmpty()) {
                val bitmap = remember(content.thumbnailBase64) {
                    try {
                        val bytes = Base64.decode(content.thumbnailBase64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        null
                    }
                }
                
                if (bitmap != null) {
                    var scale by remember { mutableStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }

                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Content Image",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    val newOffset = offset + pan
                                    offset = newOffset
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            
            if (htmlContent == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = false // Critical: No JS allowed
                            settings.domStorageEnabled = false
                            setBackgroundColor(0) // Transparent background
                            
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                    val url = request?.url?.toString()
                                    if (url != null && url.startsWith("aether://content/")) {
                                        val hash = url.substringAfter("aether://content/")
                                        onDeepLinkContent?.invoke(hash)
                                        return true
                                    }
                                    return super.shouldOverrideUrlLoading(view, request)
                                }
                            }
                            
                            loadDataWithBaseURL(null, htmlContent!!, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (onVote != null) {
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onVote(true) }) {
                        Text("👍 Like (${content.likeTokens.size})")
                    }
                    Button(onClick = { onVote(false) }) {
                        Text("👎 Dislike (${content.dislikeTokens.size})")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (onShareToGroup != null) {
                Button(
                    onClick = { onShareToGroup(content.title, "[Link to ${content.title}](aether://content/${content.contentHash})") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Share to Group")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            if (onReportContent != null) {
                OutlinedButton(
                    onClick = { showReportDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Report Content (Violates UGC Policy)")
                }

                if (showReportDialog) {
                    AlertDialog(
                        onDismissRequest = { showReportDialog = false },
                        title = { Text("Report Content") },
                        text = {
                            Column {
                                Text("Please select a reason for reporting this content:", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                val reasons = listOf(
                                    "Child Safety / CSAM / CSAE",
                                    "Harassment or Bullying",
                                    "Violence or Terrorism",
                                    "Hate Speech",
                                    "Other Community Guideline Violation"
                                )
                                
                                reasons.forEach { reason ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedReason = reason }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selectedReason == reason,
                                            onClick = { selectedReason = reason }
                                        )
                                        Text(reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Note: Reports are uploaded securely to the developer database to comply with Google Play Developer policies. Illegal content, especially CSAM, will be reported to competent national and regional authorities.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onReportContent(selectedReason)
                                    showReportDialog = false
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Submit Report")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }
            
            if (onBlockAuthor != null) {
                OutlinedButton(
                    onClick = { onBlockAuthor(content.authorAlias) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Block Author")
                }
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            if (onFlagVote != null) {
                FlagVotingSection(content = content, onFlagVote = onFlagVote)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlagVotingSection(
    content: ContentUnit,
    onFlagVote: (FlagType, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val repAgent = remember { ReputationAgent(context, KeyManager(context)) }

    val totalCategoryVotes = content.categoryTokens.values.sumOf { it.size }
    val totalEmotionVotes = content.emotionTokens.values.sumOf { it.size }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tags & Emotions: $totalCategoryVotes Categories, $totalEmotionVotes Emotions",
                    style = MaterialTheme.typography.titleSmall
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("What is this content about?", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FlagConstants.CATEGORIES.forEach { category ->
                        val tokenSet = content.categoryTokens[category] ?: emptySet()
                        val myToken = remember(content.contentHash, category) { repAgent.generateFlagToken(content.contentHash, category) }
                        val iVoted = tokenSet.contains(myToken)
                        
                        FilterChip(
                            selected = iVoted,
                            onClick = { if (!iVoted) onFlagVote(FlagType.CATEGORY, category) },
                            label = { Text("$category (${tokenSet.size})") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Text("How did this make you feel?", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FlagConstants.EMOTIONS.forEach { emotion ->
                        val tokenSet = content.emotionTokens[emotion] ?: emptySet()
                        val myToken = remember(content.contentHash, emotion) { repAgent.generateFlagToken(content.contentHash, emotion) }
                        val iVoted = tokenSet.contains(myToken)
                        
                        FilterChip(
                            selected = iVoted,
                            onClick = { if (!iVoted) onFlagVote(FlagType.EMOTION, emotion) },
                            label = { Text("$emotion (${tokenSet.size})") }
                        )
                    }
                }
            }
        }
    }
}
