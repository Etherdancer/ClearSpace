package app.clearspace.network.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.clearspace.network.storage.db.AetherDatabase
import app.clearspace.network.storage.db.entity.ContentUnit
import app.clearspace.network.storage.db.entity.Visibility

class LibraryViewModel : ViewModel() {
    private val _libraryContent = MutableStateFlow<List<ContentUnit>>(emptyList())
    val libraryContent: StateFlow<List<ContentUnit>> = _libraryContent.asStateFlow()

    private val _viewingContent = MutableStateFlow<ContentUnit?>(null)
    val viewingContent: StateFlow<ContentUnit?> = _viewingContent.asStateFlow()

    fun openContent(content: ContentUnit) {
        _viewingContent.value = content
    }

    fun clearViewingContent() {
        _viewingContent.value = null
    }

    init {
        loadContent()
    }

    fun loadContent(query: String = "") {
        viewModelScope.launch {
            try {
                val db = AetherDatabase.getPrivateDatabase()
                
                val results = if (query.isBlank()) {
                    db.contentDao().getAll()
                } else {
                    // Smart Scan: sanitize and format query to prevent FTS injection
                    val safeQuery = query.replace(Regex("[\"'\\\\*\\\\^\\\\(\\\\)\\\\[\\\\]\\\\{\\\\}:]"), "")
                    val ftsQuery = safeQuery.trim().split(Regex("\\\\s+")).filter { it.isNotBlank() }.joinToString(" ") { "$it*" }
                    if (ftsQuery.isNotBlank()) {
                        db.contentDao().searchSmartScan(ftsQuery)
                    } else {
                        emptyList()
                    }
                }
                
                _libraryContent.value = results.sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                // Not initialized
            }
        }
    }
}
