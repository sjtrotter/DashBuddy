package cloud.trotter.dashbuddy.data.event

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PickupEventDao {

    /**
     * Inserts a single pickup event.
     * Returns the new row ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: PickupEventEntity): Long

    /**
     * Gets all pickup events for a specific dash, ordered by time.
     */
    @Query("SELECT * FROM pickup_events WHERE dashId = :dashId ORDER BY timestamp ASC")
    fun getEventsForDashFlow(dashId: Long): Flow<List<PickupEventEntity>>

    /**
     * Gets the most recent pickup event.
     */
    @Query("SELECT * FROM pickup_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestEvent(): PickupEventEntity?

    /**
     * Deletes all events for a dash.
     */
    @Query("DELETE FROM pickup_events WHERE dashId = :dashId")
    suspend fun deleteEventsForDash(dashId: Long)
}