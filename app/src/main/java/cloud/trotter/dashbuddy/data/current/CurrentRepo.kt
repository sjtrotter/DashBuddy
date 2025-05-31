package cloud.trotter.dashbuddy.data.current

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import cloud.trotter.dashbuddy.log.Logger as Log

class CurrentRepo(private val currentDao: CurrentDao) {
    private val tag = "CurrentRepository"

    /**
     * Retrieves the current dash state as an observable Flow.
     * This will emit null if no current dash state is set.
     * @return A [Flow] emitting the [CurrentEntity] or null.
     */
    val currentDashStateFlow: Flow<CurrentEntity?> = currentDao.getCurrentDashStateFlow()

    /**
     * Inserts or replaces the current dash state.
     * @param currentDashState The [CurrentEntity] object to insert or replace.
     * @return The ID of the inserted/replaced row.
     */
    suspend fun upsertCurrentDashState(currentDashState: CurrentEntity) {
        withContext(Dispatchers.IO) {
            Log.d(
                tag,
                "Upserting current dash state. IsActive: ${currentDashState.isActive}, DashID: ${currentDashState.dashId}"
            )
            currentDao.upsertCurrentDashState(currentDashState)
        }
    }

    /**
     * Retrieves a snapshot of the current dash state.
     * @return The [CurrentEntity] if it exists, otherwise null.
     */
    suspend fun getCurrentDashState(): CurrentEntity? {
        return withContext(Dispatchers.IO) {
            Log.d(tag, "Getting current dash state.")
            currentDao.getCurrentDashState()
        }
    }

    /**
     * Clears the current dash state.
     * @return The number of rows deleted (should be 0 or 1).
     */
    suspend fun clearCurrentDashState() {
        withContext(Dispatchers.IO) {
            Log.i(tag, "Clearing current dash state.")
            currentDao.clearCurrentDashState()
        }
    }

    // --- Specific field update methods ---

    /**
     * Updates only the dashId and lastUpdate fields of the current dash state.
     * @param newDashId The new dash ID (can be null).
     */
    suspend fun updateDashId(newDashId: Long?) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Updating current dash ID to: $newDashId")
            currentDao.updateDashId(newDashId, System.currentTimeMillis())
        }
    }

    /**
     * Updates only the zoneId and lastUpdate fields of the current dash state.
     * @param newZoneId The new zone ID (can be null).
     */
    suspend fun updateZoneId(newZoneId: Long?) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Updating current zone ID to: $newZoneId")
            currentDao.updateZoneId(newZoneId, System.currentTimeMillis())
        }
    }

    /**
     * Updates only the isActive flag of the current dash state.
     * @param isActive The new active status.
     */
    suspend fun updateIsActive(isActive: Boolean) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Updating isActive to: $isActive")
            currentDao.updateIsActive(isActive, System.currentTimeMillis())
        }
    }

    /**
     * Updates only the isPaused flag of the current dash state.
     * @param isPaused The new paused status.
     */
    suspend fun updateIsPaused(isPaused: Boolean) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Updating isPaused to: $isPaused")
            currentDao.updateIsPaused(isPaused, System.currentTimeMillis())
        }
    }

    /**
     * Updates the lastOfferId, lastOfferValue, increments offersReceived, and sets lastUpdate.
     * @param lastOfferId The ID of the last offer.
     * @param lastOfferValue The value of the last offer.
     * @param offersReceivedIncrement Value to increment offersReceived by (typically 1).
     * @return The number of rows updated (should be 0 or 1).
     */
    suspend fun updateLastOfferInfo(
        lastOfferId: Long?,
        lastOfferValue: Double?,
        offersReceivedIncrement: Int = 1
    ) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Updating last offer info. OfferID: $lastOfferId, Value: $lastOfferValue")
            currentDao.updateLastOfferInfo(
                lastOfferId,
                lastOfferValue,
                offersReceivedIncrement,
                System.currentTimeMillis()
            )
        }
    }

    /**
     * Increments the offersAccepted count and updates lastUpdate.
     * @return The number of rows updated (should be 0 or 1).
     */
    suspend fun incrementOffersAccepted() {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Incrementing offers accepted.")
            currentDao.incrementOffersAccepted(System.currentTimeMillis())
        }
    }

    /**
     * Increments the offersDeclined count and updates lastUpdate.
     * @return The number of rows updated (should be 0 or 1).
     */
    suspend fun incrementOffersDeclined() {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Incrementing offers declined.")
            currentDao.incrementOffersDeclined(System.currentTimeMillis())
        }
    }

    /**
     * Increments the deliveriesCompleted count and updates lastUpdate.
     * @return The number of rows updated (should be 0 or 1).
     */
    suspend fun incrementDeliveriesCompleted() {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Incrementing deliveries completed.")
            currentDao.incrementDeliveriesCompleted(System.currentTimeMillis())
        }
    }

    /**
     * Updates the dashEarnings and lastUpdate.
     * @param newEarnings The new total earnings for the current dash.
     */
    suspend fun updateDashEarnings(newEarnings: Double?) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Updating dash earnings to: $newEarnings")
            currentDao.updateDashEarnings(newEarnings, System.currentTimeMillis())
        }
    }

    /**
     * Convenience function to start a new dash session in the CurrentEntity table.
     * This would typically be called when your StateMachine signals a dash is truly starting.
     * @param dashId The ID of the new dash.
     * @param zoneId The ID of the starting zone (can be null).
     * @param dashMode The mode of the dash (e.g., Earn By Time, Earn By Offer).
     * @param startTime The timestamp when the dash started.
     */
    suspend fun startNewActiveDash(
        dashId: Long,
        zoneId: Long?,
        dashMode: String?,
        startTime: Long = System.currentTimeMillis()
    ) {
        withContext(Dispatchers.IO) {
            val newState = CurrentEntity(
                id = 1,
                dashId = dashId,
                zoneId = zoneId,
                dashStartTime = startTime,
                isActive = true,
                isPaused = false,
                dashMode = dashMode,
                dashEarnings = 0.0,
                deliveriesReceived = 0,
                deliveriesCompleted = 0,
                lastOfferValue = null,
                offersReceived = 0,
                offersAccepted = 0,
                offersDeclined = 0,
                lastUpdate = startTime
            )
            Log.i(
                tag,
                "Starting new active dash in Current table. DashID: $dashId, ZoneID: $zoneId"
            )
            currentDao.upsertCurrentDashState(newState)
        }
    }

}