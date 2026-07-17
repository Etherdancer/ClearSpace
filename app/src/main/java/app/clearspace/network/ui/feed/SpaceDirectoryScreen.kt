package app.clearspace.network.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.clearspace.network.storage.db.entity.SpaceEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceDirectoryScreen(
    viewModel: SpaceViewModel,
    onSpaceSelected: (SpaceEntity) -> Unit,
    onCreateSpace: () -> Unit
) {
    val availableSpaces by viewModel.availableSpaces.collectAsState()
    val joinedSpaces by viewModel.joinedSpaces.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadGlobalSpaces()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateSpace) {
                Icon(Icons.Filled.Add, contentDescription = "Create Space")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                app.clearspace.network.ui.components.InfoCard(
                    title = "Federated Spaces",
                    description = "Spaces are moderated communities. Content published in a space is visible only within that space and is governed by its moderators."
                )
            }

            if (joinedSpaces.isNotEmpty()) {
                item {
                    Text("Your Spaces", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                }
                items(joinedSpaces) { space ->
                    SpaceCard(space, isJoined = true, onClick = { onSpaceSelected(space) }, onLeave = { viewModel.leaveSpace(space.spaceId) })
                }
            }

            item {
                Text("Discover Spaces", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            }
            items(availableSpaces.filter { a -> joinedSpaces.none { j -> j.spaceId == a.spaceId } }) { space ->
                SpaceCard(space, isJoined = false, onClick = { viewModel.joinSpace(space) }, onLeave = {})
            }
        }
    }
}

@Composable
fun SpaceCard(space: SpaceEntity, isJoined: Boolean, onClick: () -> Unit, onLeave: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(space.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (isJoined) {
                    TextButton(onClick = onLeave) { Text("Leave") }
                } else {
                    Button(onClick = onClick) { Text("Join") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(space.description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
