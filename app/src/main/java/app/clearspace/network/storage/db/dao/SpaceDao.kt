package app.clearspace.network.storage.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.clearspace.network.storage.db.entity.SpaceEntity
import app.clearspace.network.storage.db.entity.ModeratorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpaceDao {
    @Query("SELECT * FROM federated_spaces")
    fun getAllSpaces(): Flow<List<SpaceEntity>>

    @Query("SELECT * FROM federated_spaces WHERE isJoined = 1")
    fun getJoinedSpaces(): Flow<List<SpaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpace(space: SpaceEntity): Long

    @Query("UPDATE federated_spaces SET isJoined = :isJoined WHERE spaceId = :spaceId")
    suspend fun updateJoinStatus(spaceId: String, isJoined: Boolean): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModerator(moderator: ModeratorEntity): Long

    @Query("SELECT * FROM space_moderators WHERE spaceId = :spaceId")
    suspend fun getModeratorsForSpace(spaceId: String): List<ModeratorEntity>
}
