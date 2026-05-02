package io.github.whitphx.nolocationzones.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingStripDao {
    @Query("SELECT * FROM pending_strips ORDER BY detected_at ASC")
    fun observeAll(): Flow<List<PendingStripEntity>>

    @Query("SELECT * FROM pending_strips ORDER BY detected_at ASC")
    suspend fun getAll(): List<PendingStripEntity>

    @Query("SELECT COUNT(*) FROM pending_strips")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PendingStripEntity)

    @Query("DELETE FROM pending_strips WHERE imageId IN (:ids)")
    suspend fun deleteByIds(ids: Collection<Long>)

    @Query("DELETE FROM pending_strips")
    suspend fun deleteAll()
}
