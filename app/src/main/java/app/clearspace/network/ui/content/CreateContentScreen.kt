package app.clearspace.network.ui.content

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import app.clearspace.network.storage.db.entity.Visibility
import app.clearspace.network.utils.MediaUtils
import app.clearspace.network.storage.db.AetherDatabase
import app.clearspace.network.storage.db.entity.ContentUnit
import app.clearspace.network.reputation.ReputationAgent
import app.clearspace.network.crypto.KeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import java.io.File
import java.io.FileOutputStream
import app.clearspace.network.crypto.FileEncryptionUtil

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.clearspace.network.crypto.GroupEncryption
import app.clearspace.network.storage.db.entity.GroupMember
import app.clearspace.network.persona.PersonaAgent

val CATEGORIES = listOf(
    "Politics", "Religion", "Sports", "Music", "News", "Science", "Technology", "Art", "Literature", "History", 
    "Philosophy", "Health", "Food", "Travel", "Nature", "Comedy", "Education", "DIY", "Finance", "Fashion", 
    "Gaming", "Movies", "TV", "Photography", "Podcasts", "Automotive", "Pets", "Parenting", "Relationships", 
    "Career", "Fitness", "Mental Health", "Culture", "Language", "Architecture", "Gardening", "Crafts", 
    "Collecting", "Volunteering", "Local Events", "Other"
)

val EMOTIONS = listOf(
    "Happy", "Sad", "Cheerful", "Angry", "Inspired", "Anxious", "Calm", "Excited", "Nostalgic", "Amused", 
    "Hopeful", "Frustrated", "Grateful", "Confused", "Proud", "Disgusted", "Surprised", "Moved", "Bored", 
    "Scared", "Empowered", "Lonely", "Peaceful", "Curious", "Overwhelmed", "Determined", "Melancholic", 
    "Playful", "Tender", "Rebellious"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateContentScreen(modifier: Modifier = Modifier, onNavigateBack: () -> Unit, initialTitle: String = "", initialBody: String = "", forceVisibilityGroup: Boolean = false, spaceId: String? = null) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var title by remember { mutableStateOf(initialTitle) }
    var body by remember { mutableStateOf(initialBody) }
    var visibility by remember { mutableStateOf(if (forceVisibilityGroup) Visibility.GROUP else Visibility.PRIVATE) }
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var isVideo by remember { mutableStateOf(false) }
    var fileEncryptionPassphrase by remember { mutableStateOf("") }
    var selectedEncryptedFileUri by remember { mutableStateOf<Uri?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    val db = AetherDatabase.getPrivateDatabase()
    val groupDao = db.groupDao()
    val groups by groupDao.getAllGroups().collectAsState(initial = emptyList())
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    
    val selectedCategories = remember { mutableStateListOf<String>() }
    val selectedEmotions = remember { mutableStateListOf<String>() }
    var expandCategories by remember { mutableStateOf(false) }
    var expandEmotions by remember { mutableStateOf(false) }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedMediaUri = uri
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedEncryptedFileUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Content") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Content Editor", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = showPreview,
                    onCheckedChange = { showPreview = it }
                )
                Text("Live Preview")
            }
            if (showPreview) {
                Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp), tonalElevation = 2.dp) {
                    var htmlContent by remember { mutableStateOf<String?>(null) }
                    
                    DisposableEffect(body) {
                        var binder: app.clearspace.network.IContentRenderer? = null
                        val connection = object : android.content.ServiceConnection {
                            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                                binder = app.clearspace.network.IContentRenderer.Stub.asInterface(service)
                                try {
                                    htmlContent = binder?.renderMarkdownToHtml(body) ?: "Renderer error"
                                } catch (e: Exception) {
                                    htmlContent = "Error: ${e.message}"
                                }
                            }
                            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                                binder = null
                            }
                        }
                        
                        val intent = android.content.Intent(context, app.clearspace.network.security.guard.ContentRendererService::class.java)
                        context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE)
                        
                        onDispose {
                            context.unbindService(connection)
                        }
                    }

                    if (htmlContent == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    settings.javaScriptEnabled = false
                                    settings.domStorageEnabled = false
                                    setBackgroundColor(0)
                                    loadDataWithBaseURL(null, htmlContent!!, "text/html", "UTF-8", null)
                                }
                            },
                            update = { webView ->
                                webView.loadDataWithBaseURL(null, htmlContent!!, "text/html", "UTF-8", null)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Content") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    maxLines = 10
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("Visibility")
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = visibility == Visibility.PRIVATE,
                    onClick = { visibility = Visibility.PRIVATE },
                    label = { Text("Private") }
                )
                FilterChip(
                    selected = visibility == Visibility.TRUSTED,
                    onClick = { visibility = Visibility.TRUSTED },
                    label = { Text("Trusted") }
                )
                FilterChip(
                    selected = visibility == Visibility.PUBLIC,
                    onClick = { visibility = Visibility.PUBLIC },
                    label = { Text("Public") }
                )
                FilterChip(
                    selected = visibility == Visibility.GROUP,
                    onClick = { visibility = Visibility.GROUP },
                    label = { Text("Group") }
                )
            }
            
            if (visibility == Visibility.GROUP) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Select Group", style = MaterialTheme.typography.labelMedium)
                if (groups.isEmpty()) {
                    Text("No groups available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    groups.forEach { group ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedGroupId == group.groupId,
                                onClick = { selectedGroupId = group.groupId }
                            )
                            Text(group.name)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("Categories (${selectedCategories.size} selected)", style = MaterialTheme.typography.labelLarge)
            Button(onClick = { expandCategories = !expandCategories }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expandCategories) "Collapse Categories" else "Expand Categories")
            }
            if (expandCategories) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORIES.forEach { cat ->
                        FilterChip(
                            selected = selectedCategories.contains(cat),
                            onClick = { 
                                if (selectedCategories.contains(cat)) selectedCategories.remove(cat) 
                                else selectedCategories.add(cat) 
                            },
                            label = { Text(cat) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Text("Emotions (${selectedEmotions.size} selected)", style = MaterialTheme.typography.labelLarge)
            Button(onClick = { expandEmotions = !expandEmotions }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expandEmotions) "Collapse Emotions" else "Expand Emotions")
            }
            if (expandEmotions) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EMOTIONS.forEach { emo ->
                        FilterChip(
                            selected = selectedEmotions.contains(emo),
                            onClick = { 
                                if (selectedEmotions.contains(emo)) selectedEmotions.remove(emo) 
                                else selectedEmotions.add(emo) 
                            },
                            label = { Text(emo) }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { 
                    isVideo = false
                    mediaPicker.launch("image/*") 
                }) {
                    Text("Attach Image")
                }
                Button(onClick = { 
                    isVideo = true
                    mediaPicker.launch("video/*") 
                }) {
                    Text("Attach Video")
                }
            }
            Button(onClick = { 
                filePicker.launch("*/*") 
            }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Attach Encrypted File")
            }
            
            if (selectedMediaUri != null) {
                Text("Media attached: ${if (isVideo) "Video" else "Image"}", color = MaterialTheme.colorScheme.primary)
            }
            if (selectedEncryptedFileUri != null) {
                val fileName = selectedEncryptedFileUri?.lastPathSegment ?: "Unknown File"
                Text("File attached for encryption: $fileName", color = MaterialTheme.colorScheme.primary)
                var passwordVisible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = fileEncryptionPassphrase,
                    onValueChange = { fileEncryptionPassphrase = it },
                    label = { Text("Encryption Passphrase") },
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            var isError by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf("") }
            
            if (isError) {
                Text(errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
            }

            Button(
                onClick = {
                    if (title.isBlank()) {
                        isError = true
                        errorMessage = "Title cannot be empty"
                        return@Button
                    }

                    coroutineScope.launch {
                        if (visibility == Visibility.PUBLIC || visibility == Visibility.TRUSTED) {
                            val filterAgent = app.clearspace.network.security.guard.ContentFilterAgent(context)
                            if (!filterAgent.isTextSafe(title) || !filterAgent.isTextSafe(body)) {
                                isError = true
                                errorMessage = "This content contains words flagged by the on-device safety filter and cannot be broadcast."
                                return@launch
                            }
                            if (selectedMediaUri != null) {
                                val isSafe = filterAgent.isImageSafe(selectedMediaUri!!)
                                if (!isSafe) {
                                    isError = true
                                    errorMessage = "This image was flagged by the on-device safety filter and cannot be broadcast."
                                    return@launch
                                }
                            }
                        }

                        // Save to database using AetherDatabase
                        val sharedDb = AetherDatabase.getSharedDatabase()
                        
                        var imgPath: String? = null
                        var vidPath: String? = null
                        var thumb64: String? = null
                        
                        selectedMediaUri?.let { uri ->
                            if (isVideo) {
                                val res = MediaUtils.copyAndSaveVideo(context, uri)
                                vidPath = res?.first
                                thumb64 = res?.second
                            } else {
                                val res = MediaUtils.compressAndSaveImage(context, uri)
                                imgPath = res?.first
                                thumb64 = res?.second
                            }
                        }
                        
                        selectedEncryptedFileUri?.let { uri ->
                            if (fileEncryptionPassphrase.isNotEmpty()) {
                                val inputStream = context.contentResolver.openInputStream(uri)
                                if (inputStream != null) {
                                    val outFile = File(context.filesDir, "encrypted_${System.currentTimeMillis()}.aether")
                                    val outputStream = FileOutputStream(outFile)
                                    FileEncryptionUtil.encrypt(inputStream, outputStream, fileEncryptionPassphrase.toCharArray())
                                    body += "\n\n[Encrypted File Attached: ${outFile.name}]"
                                }
                            }
                        }
                        
                        val contentRaw = title + body + System.currentTimeMillis() + UUID.randomUUID().toString()
                        val md = MessageDigest.getInstance("SHA-256")
                        val hashBytes = md.digest(contentRaw.toByteArray())
                        val hashString = hashBytes.joinToString("") { "%02x".format(it) }
                        
                        withContext(Dispatchers.IO) {
                            val repAgent = ReputationAgent(context, KeyManager(context))
                            val nonce = ReputationAgent.generateProofOfWork(hashString)
                            
                            var bodyToSave = body
                            var recipientKeyMapJsonToSave: String? = null
                            
                            if (visibility == Visibility.GROUP && selectedGroupId != null) {
                                val members = groupDao.getGroupMembersSync(selectedGroupId!!)
                                val recipientPublicKeys = members.mapNotNull {
                                    if (it.encryptionPublicKeyBase64 != null) {
                                        it.publicKeyBase64 to it.encryptionPublicKeyBase64
                                    } else null
                                }.toMap()
                                
                                if (recipientPublicKeys.isNotEmpty()) {
                                    val aesKey = ByteArray(32).apply { java.security.SecureRandom().nextBytes(this) }
                                    recipientKeyMapJsonToSave = GroupEncryption.wrapKeyForRecipients(aesKey, recipientPublicKeys)
                                    
                                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                                    val keySpec = javax.crypto.spec.SecretKeySpec(aesKey, "AES")
                                    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
                                    val iv = cipher.iv
                                    val encryptedBody = cipher.doFinal(body.toByteArray())
                                    bodyToSave = android.util.Base64.encodeToString(iv + encryptedBody, android.util.Base64.NO_WRAP)
                                }
                            }
                            
                            val unit = ContentUnit(
                                contentHash = hashString,
                                title = title,
                                body = bodyToSave,
                                categoryFlags = selectedCategories.joinToString(","),
                                emotionFlags = selectedEmotions.joinToString(","),
                                visibility = visibility,
                                authorAlias = PersonaAgent(context, KeyManager(context)).getProfile()?.alias ?: "Anonymous",
                                timestamp = System.currentTimeMillis(),
                                importCount = 0,
                                powNonce = nonce,
                                likeTokens = emptySet(),
                                dislikeTokens = emptySet(),
                                reportTokens = emptySet(),
                                categoryTokens = emptyMap(),
                                emotionTokens = emptyMap(),
                                imagePath = imgPath,
                                videoPath = vidPath,
                                thumbnailBase64 = thumb64,
                                recipientKeyMapJson = recipientKeyMapJsonToSave,
                                spaceId = spaceId
                            )
                            
                            val unitJson = kotlinx.serialization.json.Json.encodeToString(app.clearspace.network.storage.db.entity.ContentUnit.serializer(), unit)
                            
                            AetherDatabase.getPrivateDatabase().contentDao().insert(unit)
                            
                            if (visibility != Visibility.PRIVATE) {
                                sharedDb.contentDao().insert(unit)
                                app.clearspace.network.storage.StorageQuotaManager.enforcePublicQuota(context)
                                
                                if (visibility == Visibility.TRUSTED || visibility == Visibility.GROUP) {
                                    val serviceIntent = android.content.Intent(context, app.clearspace.network.network.FirestoreDeadDropService::class.java).apply {
                                        action = app.clearspace.network.network.FirestoreDeadDropService.ACTION_UPLOAD
                                        putExtra(app.clearspace.network.network.FirestoreDeadDropService.EXTRA_CONTENT_JSON, unitJson)
                                    }
                                    context.startService(serviceIntent)
                                }
                            }
                        }
                        onNavigateBack()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}
