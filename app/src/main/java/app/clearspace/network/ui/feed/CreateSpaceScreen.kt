package app.clearspace.network.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.clearspace.network.storage.db.entity.SpaceEntity
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSpaceScreen(
    onBack: () -> Unit,
    viewModel: SpaceViewModel,
    creatorAlias: String
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Space") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Space Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = rules,
                onValueChange = { rules = it },
                label = { Text("Rules (Markdown supported)") },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        isSubmitting = true
                        scope.launch {
                            val spaceId = UUID.randomUUID().toString()
                            val space = SpaceEntity(
                                spaceId = spaceId,
                                name = name,
                                description = description,
                                isJoined = true,
                                creatorAlias = creatorAlias,
                                rules = rules
                            )
                            try {
                                FirebaseFirestore.getInstance().collection("federated_spaces").document(spaceId).set(space).await()
                                viewModel.joinSpace(space)
                                viewModel.loadGlobalSpaces()
                                onBack()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            } finally {
                                isSubmitting = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting && name.isNotBlank()
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Text("Create Space")
                }
            }
        }
    }
}
