package cloud.trotter.dashbuddy.data.event

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DashEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: DashEventEntity): Long

    /**
     * Gets all dash lifecycle events, ordered by time.
     * Use this to reconstruct sessions (Pairing STARTs with STOPs).
     */
    @Query("SELECT * FROM dash_events ORDER BY timestamp DESC")
    fun getAllEventsFlow(): Flow<List<DashEventEntity>>

    /**
     * Gets the most recent event.
     * Useful to check "Am I currently dashing?" (If last event was START, yes. If STOP, no.)
     */
    @Query("SELECT * FROM dash_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestEvent(): DashEventEntity?
}