package cloud.trotter.dashbuddy.core.database.snapshot

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppStateSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AppStateSnapshotEntity)

    /** Most recent snapshot for crash recovery. */
    @Query("SELECT * FROM app_state_snapshots ORDER BY correlationVersion DESC LIMIT 1")
    suspend fun latest(): AppStateSnapshotEntity?

    /** Prune snapshots older than [cutoff] millis. */
    @Query("DELETE FROM app_state_snapshots WHERE capturedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}
