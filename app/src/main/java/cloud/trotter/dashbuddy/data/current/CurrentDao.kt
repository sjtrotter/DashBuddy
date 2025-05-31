package cloud.trotter.dashbuddy.data.current

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the current_dash table, which stores the state of the
 * currently active dash session. This table is expected to have at most one row.
 */
@Dao
interface CurrentDao {

    /**
     * Inserts or replaces the current dash state.
     * Since the primary key is fixed (e.g., id = 1), this effectively acts as an "upsert"
     * for the single row representing the current dash state.
     *
     * @param currentDashState The [CurrentEntity] object to insert or replace.
     * @return The ID of the inserted/replaced row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCurrentDashState(currentDashState: CurrentEntity)

    /**
     * Retrieves the current dash state as an observable Flow.
     * This will emit null if no current dash state is set (i.e., the row doesn't exist).
     *
     * @return A [Flow] emitting the [CurrentEntity] or null.
     */
    @Query("SELECT * FROM current_dash WHERE id = 1 LIMIT 1")
    fun getCurrentDashStateFlow(): Flow<CurrentEntity?>

    /**
     * Retrieves a snapshot of the current dash state.
     * This is a one-time fetch.
     *
     * @return The [CurrentEntity] if it exists, otherwise null.
     */
    @Query("SELECT * FROM current_dash WHERE id = 1 LIMIT 1")
    suspend fun getCurrentDashState(): CurrentEntity?

    /**
     * Clears the current dash state by deleting the row.
     * This would be used when a dash ends or when resetting state.
     *
     * @return The number of rows deleted (should be 0 or 1).
     */
    @Query("DELETE FROM current_dash WHERE id = 1")
    suspend fun clearCurrentDashState(): Int

    // --- Specific field update methods ---

    /**
     * Updates only the dashId and lastUpdate fields of the current dash state.
     * @param newDashId The new dash ID (can be null).
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET dashId = :newDashId, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateDashId(newDashId: Long?, timestamp: Long)

    /**
     * Updates only the zoneId and lastUpdate fields of the current dash state.
     * @param newZoneId The new zone ID (can be null).
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET zoneId = :newZoneId, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateZoneId(newZoneId: Long?, timestamp: Long)

    /**
     * Updates only the isActive flag of the current dash state.
     * @param isActive The new active status.
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET isActive = :isActive, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateIsActive(isActive: Boolean, timestamp: Long)

    /**
     * Updates only the isPaused flag of the current dash state.
     * @param isPaused The new paused status.
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET isPaused = :isPaused, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateIsPaused(isPaused: Boolean, timestamp: Long)

    /**
     * Updates the lastOfferId, lastOfferValue, increments offersReceived, and sets lastUpdate.
     * @param lastOfferId The ID of the last offer.
     * @param lastOfferValue The value of the last offer.
     * @param offersReceivedIncrement Value to increment offersReceived by (typically 1).
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET lastOfferId = :lastOfferId, lastOfferValue = :lastOfferValue, offersReceived = offersReceived + :offersReceivedIncrement, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateLastOfferInfo(
        lastOfferId: Long?,
        lastOfferValue: Double?,
        offersReceivedIncrement: Int = 1,
        timestamp: Long
    )

    /**
     * Increments the offersAccepted count and updates lastUpdate.
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET offersAccepted = offersAccepted + 1, lastUpdate = :timestamp WHERE id = 1")
    suspend fun incrementOffersAccepted(timestamp: Long)

    /**
     * Increments the offersDeclined count and updates lastUpdate.
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET offersDeclined = offersDeclined + 1, lastUpdate = :timestamp WHERE id = 1")
    suspend fun incrementOffersDeclined(timestamp: Long)

    /**
     * Increments the deliveriesCompleted count and updates lastUpdate.
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET deliveriesCompleted = deliveriesCompleted + 1, lastUpdate = :timestamp WHERE id = 1")
    suspend fun incrementDeliveriesCompleted(timestamp: Long)

    /**
     * Updates the dashEarnings and lastUpdate.
     * @param newEarnings The new total earnings for the current dash.
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET dashEarnings = :newEarnings, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateDashEarnings(newEarnings: Double?, timestamp: Long)
}
