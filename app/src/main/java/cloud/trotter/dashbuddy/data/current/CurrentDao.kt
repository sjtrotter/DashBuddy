package cloud.trotter.dashbuddy.data.current

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cloud.trotter.dashbuddy.data.dash.DashType
import kotlinx.coroutines.flow.Flow

@Dao
interface CurrentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCurrentDashState(currentDashState: CurrentEntity)

    @Query("SELECT * FROM current_dash WHERE id = 1 LIMIT 1")
    fun getCurrentDashStateFlow(): Flow<CurrentEntity?>

    @Query("SELECT * FROM current_dash WHERE id = 1 LIMIT 1")
    suspend fun getCurrentDashState(): CurrentEntity?

    @Query("DELETE FROM current_dash WHERE id = 1")
    suspend fun clearCurrentDashState(): Int

    // --- Specific field update methods ---

    @Query("UPDATE current_dash SET dashId = :newDashId, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateDashId(newDashId: Long?, timestamp: Long)

    @Query("UPDATE current_dash SET zoneId = :newZoneId, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateZoneId(newZoneId: Long?, timestamp: Long)

    @Query("UPDATE current_dash SET isActive = :isActive, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateIsActive(isActive: Boolean, timestamp: Long)

    @Query("UPDATE current_dash SET isPaused = :isPaused, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateIsPaused(isPaused: Boolean, timestamp: Long)

    @Query("UPDATE current_dash SET dashType = :dashType, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateDashType(dashType: DashType, timestamp: Long)

    @Query("UPDATE current_dash SET zoneId = :newZoneId, dashType = :newDashType, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updatePreDashInfo(newZoneId: Long?, newDashType: DashType?, timestamp: Long)

    @Query("UPDATE current_dash SET lastOfferId = :lastOfferId, lastOfferValue = :lastOfferValue, offersReceived = offersReceived + :offersReceivedIncrement, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateLastOfferInfo(
        lastOfferId: Long?,
        lastOfferValue: Double?,
        offersReceivedIncrement: Int = 1,
        timestamp: Long
    )

    @Query("UPDATE current_dash SET offersAccepted = offersAccepted + 1, lastUpdate = :timestamp WHERE id = 1")
    suspend fun incrementOffersAccepted(timestamp: Long)

    @Query("UPDATE current_dash SET offersDeclined = offersDeclined + 1, lastUpdate = :timestamp WHERE id = 1")
    suspend fun incrementOffersDeclined(timestamp: Long)

    @Query("UPDATE current_dash SET deliveriesCompleted = deliveriesCompleted + 1, lastUpdate = :timestamp WHERE id = 1")
    suspend fun incrementDeliveriesCompleted(timestamp: Long)

    @Query("UPDATE current_dash SET dashEarnings = :newEarnings, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateDashEarnings(newEarnings: Double?, timestamp: Long)

    // --- New DAO Methods for Active Order Tracking ---

    /**
     * Updates only the activeOrderId field.
     * @param orderId The ID of the currently active order, or null if none.
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET activeOrderId = :orderId, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateActiveOrderId(orderId: Long?, timestamp: Long)

    /**
     * Updates only the activeOrderQueue field.
     * @param orderQueue The complete new list of active order IDs.
     * @param timestamp The current time for lastUpdate.
     */
    @Query("UPDATE current_dash SET activeOrderQueue = :orderQueue, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateActiveOrderQueue(orderQueue: List<Long>, timestamp: Long)

    @Query("UPDATE current_dash SET activeOrderId = :newActiveOrderId, activeOrderQueue = :newQueue, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateActiveOrderAndQueue(
        newActiveOrderId: Long,
        newQueue: List<Long>,
        timestamp: Long
    )

    // Add this query to update the last known location coordinates
    @Query("UPDATE current_dash SET lastLatitude = :latitude, lastLongitude = :longitude, lastUpdate = :timestamp WHERE id = 1")
    suspend fun updateLastLocation(latitude: Double, longitude: Double, timestamp: Long)
}
