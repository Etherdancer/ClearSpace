package app.clearspace.network.storage.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "federated_spaces")
data class SpaceEntity(
    @PrimaryKey val spaceId: String, // Public Key of the Space Creator
    val name: String,
    val description: String,
    val isJoined: Boolean = false,
    val creatorAlias: String,
    val rules: String,
    val createdAt: Long = System.currentTimeMillis()
)
