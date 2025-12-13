package cloud.trotter.dashbuddy.data.event

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AppEventEntity): Long

    /** * Returns all events, ordered by sequence.
     * Useful for replaying the entire history of the app.
     */
    @Query("SELECT * FROM app_events ORDER BY sequenceId ASC")
    fun getAllEvents(): Flow<List<AppEventEntity>>

    /**
     * Returns all events for a specific Dash session.
     */
    @Query("SELECT * FROM app_events WHERE aggregateId = :dashId ORDER BY sequenceId ASC")
    fun getEventsForDash(dashId: String): Flow<List<AppEventEntity>>

    /**
     * Returns all events of a specific type (e.g., all OFFER_RECEIVED events).
     * Room automatically converts the Enum parameter to a String for the query.
     */
    @Query("SELECT * FROM app_events WHERE eventType = :type ORDER BY occurredAt DESC")
    fun getEventsByType(type: AppEventType): Flow<List<AppEventEntity>>

    /**
     * Returns all events that happened between the start and end timestamps.
     * Useful for daily/weekly summaries or debugging a specific time window.
     */
    @Query("SELECT * FROM app_events WHERE occurredAt BETWEEN :startTime AND :endTime ORDER BY occurredAt ASC")
    fun getEventsInTimeRange(startTime: Long, endTime: Long): Flow<List<AppEventEntity>>
}