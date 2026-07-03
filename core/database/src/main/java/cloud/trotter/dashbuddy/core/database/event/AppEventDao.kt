package cloud.trotter.dashbuddy.core.database.event

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
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
    fun getEventsForSession(dashId: String): Flow<List<AppEventEntity>>

    /**
     * The dash ([aggregateId]) of the most recent event, or null if none. The
     * durable "last completed dash" fallback for the bubble HUD when no session
     * is live (#459): the in-memory scan latch that fed `displayedSessionId` was
     * wiped by process death (8a) and by a post-dash bubble re-subscribe (8b),
     * emptying the chat/cards. The event log survives both, so the bubble
     * reviews the last dash until the next one starts.
     */
    @Query("SELECT aggregateId FROM app_events WHERE aggregateId IS NOT NULL ORDER BY sequenceId DESC LIMIT 1")
    fun getMostRecentSessionId(): Flow<String?>

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

    /**
     * A page of events after [after] (exclusive), oldest-first, capped at [limit].
     * The analytics projector's paged fold input (#314): it walks the log forward
     * from its watermark in bounded batches, so memory stays flat over a long log.
     */
    @Query("SELECT * FROM app_events WHERE sequenceId > :after ORDER BY sequenceId ASC LIMIT :limit")
    suspend fun getEventsAfter(after: Long, limit: Int): List<AppEventEntity>

    /**
     * The highest sequenceId in the log, or null when the log is empty. The
     * analytics projector's trigger (#314): Room invalidation re-emits this on
     * every insert, so a rising max wakes the catch-up fold.
     */
    @Query("SELECT MAX(sequenceId) FROM app_events")
    fun maxSequenceId(): Flow<Long?>
}