package app.clearspace.network.storage.db.entity

import androidx.room.Entity

@Entity(tableName = "space_moderators", primaryKeys = ["spaceId", "moderatorPubKey"])
data class ModeratorEntity(
    val spaceId: String,
    val moderatorPubKey: String,
    val addedAt: Long = System.currentTimeMillis()
)
