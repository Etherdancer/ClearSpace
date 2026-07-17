package app.clearspace.network.ui.feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.clearspace.network.storage.db.AetherDatabase
import app.clearspace.network.storage.db.entity.SpaceEntity
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SpaceViewModel(application: Application) : AndroidViewModel(application) {
    private val db = FirebaseFirestore.getInstance()
    private val localDb = AetherDatabase.getPrivateDatabase()

    private val _availableSpaces = MutableStateFlow<List<SpaceEntity>>(emptyList())
    val availableSpaces: StateFlow<List<SpaceEntity>> = _availableSpaces.asStateFlow()

    private val _joinedSpaces = MutableStateFlow<List<SpaceEntity>>(emptyList())
    val joinedSpaces: StateFlow<List<SpaceEntity>> = _joinedSpaces.asStateFlow()

    init {
        // Observe locally joined spaces
        viewModelScope.launch(Dispatchers.IO) {
            localDb.spaceDao().getJoinedSpaces().collect { spaces ->
                _joinedSpaces.value = spaces
            }
        }
    }

    fun loadGlobalSpaces() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = db.collection("federated_spaces").get().await()
                val spaces = result.documents.mapNotNull { doc ->
                    val spaceId = doc.id
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val description = doc.getString("description") ?: ""
                    val creatorAlias = doc.getString("creatorAlias") ?: ""
                    val rules = doc.getString("rules") ?: ""
                    val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                    SpaceEntity(spaceId, name, description, false, creatorAlias, rules, createdAt)
                }
                _availableSpaces.value = spaces
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun joinSpace(space: SpaceEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val joinedSpace = space.copy(isJoined = true)
            localDb.spaceDao().insertSpace(joinedSpace)
        }
    }

    fun leaveSpace(spaceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            localDb.spaceDao().updateJoinStatus(spaceId, false)
        }
    }
}
