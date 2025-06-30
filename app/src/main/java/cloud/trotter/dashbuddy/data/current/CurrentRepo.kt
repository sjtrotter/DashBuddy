package cloud.trotter.dashbuddy.data.current

import cloud.trotter.dashbuddy.data.dash.DashType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import cloud.trotter.dashbuddy.log.Logger as Log

class CurrentRepo(private val currentDao: CurrentDao) {
    private val tag = "CurrentRepository"

    val currentDashStateFlow: Flow<CurrentEntity?> = currentDao.getCurrentDashStateFlow()

    suspend fun upsertCurrentDashState(currentDashState: CurrentEntity) {
        withContext(Dispatchers.IO) {
            Log.d(
                tag,
                "Upserting current dash state. IsActive: ${
                    currentDashState.isActive
                }, DashID: ${
                    currentDashState.dashId
                }"
            )
            currentDao.upsertCurrentDashState(currentDashState)
        }
    }

    suspend fun getCurrentDashState(): CurrentEntity? {
        return withContext(Dispatchers.IO) {
            Log.d(tag, "Getting current dash state.")
            currentDao.getCurrentDashState()
        }
    }

    suspend fun clearCurrentDashState() {
        withContext(Dispatchers.IO) {
            Log.i(tag, "Clearing current dash state.")
            currentDao.clearCurrentDashState()
        }
    }

    // --- Specific field update methods ---

    suspend fun updateDashId(newDashId: Long?) {
        withContext(Dispatchers.IO) {
            currentDao.updateDashId(
                newDashId,
                System.currentTimeMillis()
            )
        }
    }

    suspend fun updateZoneId(newZoneId: Long?) {
        withContext(Dispatchers.IO) {
            currentDao.updateZoneId(
                newZoneId,
                System.currentTimeMillis()
            )
        }
    }

    suspend fun updateDashType(dashType: DashType) {
        withContext(Dispatchers.IO) {
            currentDao.updateDashType(
                dashType,
                System.currentTimeMillis()
            )
        }
    }

    suspend fun updateIsActive(isActive: Boolean) {
        withContext(Dispatchers.IO) {
            currentDao.updateIsActive(
                isActive,
                System.currentTimeMillis()
            )
        }
    }

    suspend fun updateIsPaused(isPaused: Boolean) {
        withContext(Dispatchers.IO) {
            currentDao.updateIsPaused(
                isPaused,
                System.currentTimeMillis()
            )
        }
    }

    suspend fun updateLastOfferInfo(
        lastOfferId: Long?,
        lastOfferValue: Double?,
        offersReceivedIncrement: Int = 1
    ) {
        withContext(Dispatchers.IO) {
            currentDao.updateLastOfferInfo(
                lastOfferId,
                lastOfferValue,
                offersReceivedIncrement,
                System.currentTimeMillis()
            )
        }
    }

    suspend fun incrementOffersAccepted() {
        withContext(Dispatchers.IO) {
            currentDao.incrementOffersAccepted(System.currentTimeMillis())
        }
    }

    suspend fun incrementOffersDeclined() {
        withContext(Dispatchers.IO) {
            currentDao.incrementOffersDeclined(System.currentTimeMillis())
        }
    }

    suspend fun incrementDeliveriesCompleted() {
        withContext(Dispatchers.IO) {
            currentDao.incrementDeliveriesCompleted(System.currentTimeMillis())
        }
    }

    suspend fun updateDashEarnings(newEarnings: Double?) {
        withContext(Dispatchers.IO) {
            currentDao.updateDashEarnings(
                newEarnings,
                System.currentTimeMillis()
            )
        }
    }

    // --- New Repository Methods for Active Order Tracking ---

    /**
     * Sets the single active order ID, which indicates the primary task
     * the dasher is currently focused on (e.g., driving to this store).
     */
    suspend fun setActiveOrderId(orderId: Long?) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Setting active order ID to: $orderId")
            currentDao.updateActiveOrderId(orderId, System.currentTimeMillis())
        }
    }

    /**
     * Appends new order IDs to the existing active order queue.
     */
    suspend fun addOrdersToQueue(newOrderIds: List<Long>) {
        if (newOrderIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            val current = currentDao.getCurrentDashState()
            if (current != null) {
                // Combine existing and new IDs, and remove duplicates to be safe
                val updatedQueue = (current.activeOrderQueue + newOrderIds).distinct()
                Log.i(tag, "Adding orders to queue. New queue: $updatedQueue")
                currentDao.updateActiveOrderQueue(updatedQueue, System.currentTimeMillis())
            } else {
                Log.w(tag, "Cannot add orders to queue, current dash state is null.")
            }
        }
    }

    /**
     * Removes a completed order ID from the active order queue.
     * Also clears the activeOrderId if it was the one being completed.
     */
    suspend fun removeOrderFromQueue(completedOrderId: Long) {
        withContext(Dispatchers.IO) {
            val current = currentDao.getCurrentDashState()
            if (current != null) {
                val updatedQueue = current.activeOrderQueue.toMutableList().apply {
                    remove(completedOrderId)
                }
                Log.i(
                    tag,
                    "Removing order $completedOrderId from queue. New queue: $updatedQueue"
                )
                currentDao.updateActiveOrderQueue(updatedQueue, System.currentTimeMillis())

                // If the completed order was the "active" one, clear it.
                if (current.activeOrderId == completedOrderId) {
                    Log.d(tag, "Clearing active order ID as it was the completed one.")
                    currentDao.updateActiveOrderId(null, System.currentTimeMillis())
                }
            } else {
                Log.w(tag, "Cannot remove order from queue, current dash state is null.")
            }
        }
    }

    /**
     * Convenience function to start a new dash session.
     * Initializes the entire CurrentEntity row.
     */
    suspend fun startNewActiveDash(
        dashId: Long,
        zoneId: Long?,
        dashType: DashType?,
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
                dashType = dashType,
                dashEarnings = 0.0,
                deliveriesReceived = 0,
                deliveriesCompleted = 0,
                lastOfferId = null,
                lastOfferValue = null,
                offersReceived = 0,
                offersAccepted = 0,
                offersDeclined = 0,
                lastUpdate = System.currentTimeMillis(),
                activeOrderId = null,
                activeOrderQueue = emptyList()
            )
            Log.i(
                tag,
                "Starting new active dash in Current table. DashID: $dashId, ZoneID: $zoneId"
            )
            currentDao.upsertCurrentDashState(newState)
        }
    }

    /**
     * Updates which order is the primary focus. It sets the new activeOrderId,
     * adds the old one (if any) back to the queue, and removes the new one from the queue.
     */
    suspend fun updateActiveOrderFocus(newlyActiveOrderId: Long) {
        withContext(Dispatchers.IO) {
            val current = currentDao.getCurrentDashState()
            if (current != null) {
                // If the requested active order is already the active one, do nothing.
                if (current.activeOrderId == newlyActiveOrderId) {
                    Log.d(tag, "Order $newlyActiveOrderId is already active. No change needed.")
                    return@withContext
                }

                val oldActiveOrderId = current.activeOrderId
                val newQueue = current.activeOrderQueue.toMutableList()

                // 1. Add the old active order ID back to the queue, if there was one.
                if (oldActiveOrderId != null) {
                    newQueue.add(oldActiveOrderId)
                }

                // 2. Remove the new active order ID from the queue.
                newQueue.remove(newlyActiveOrderId)

                Log.i(
                    tag,
                    "Swapping active order. Old: $oldActiveOrderId, New: $newlyActiveOrderId. New Queue: $newQueue"
                )

                // 3. Perform the database update in a single transaction.
                currentDao.updateActiveOrderAndQueue(
                    newActiveOrderId = newlyActiveOrderId,
                    newQueue = newQueue.distinct(), // Use distinct() for safety
                    timestamp = System.currentTimeMillis()
                )
            } else {
                Log.w(tag, "Cannot update active order focus, current dash state is null.")
            }
        }
    }

    suspend fun updatePreDashInfo(newZoneId: Long?, newDashType: DashType?) {
        withContext(Dispatchers.IO) {
            // 1. Get the current state from the DB, or create a fresh default entity if it's null.
            // The 'id = 1' ensures we're always working with the single row.
            val currentState = currentDao.getCurrentDashState() ?: CurrentEntity(id = 1)

            // 2. Create the new, updated entity in memory using .copy()
            val updatedState = currentState.copy(
                zoneId = newZoneId,
                dashType = newDashType,
                lastUpdate = System.currentTimeMillis()
            )

            // 3. Perform a single, atomic upsert operation.
            // This will INSERT the row if it's the first time, or
            // REPLACE the existing row with the updated data.
            currentDao.upsertCurrentDashState(updatedState)

            Log.i(tag, "Upserted pre-dash info. ZoneID: $newZoneId, Mode: $newDashType")
        }
    }
}
