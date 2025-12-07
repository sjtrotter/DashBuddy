package cloud.trotter.dashbuddy.data.event

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import cloud.trotter.dashbuddy.log.Logger as Log

class PickupEventRepo(private val pickupEventDao: PickupEventDao) {
    private val tag = "PickupEventRepo"

    /**
     * Inserts a new pickup event. Ensures operation runs on IO dispatcher.
     */
    suspend fun insert(event: PickupEventEntity): Long {
        return withContext(Dispatchers.IO) {
            Log.v(tag, "Inserting pickup event: ${event.rawStoreName} - ${event.status}")
            pickupEventDao.insert(event)
        }
    }

    /**
     * Retrieves the most recently recorded event.
     */
    suspend fun getLatestEvent(): PickupEventEntity? {
        return withContext(Dispatchers.IO) {
            pickupEventDao.getLatestEvent()
        }
    }

    /**
     * Retrieves all events for a specific dash as a Flow.
     */
    fun getEventsForDashFlow(dashId: Long): Flow<List<PickupEventEntity>> {
        return pickupEventDao.getEventsForDashFlow(dashId)
    }

    /**
     * Deletes events for a specific dash.
     */
    suspend fun deleteEventsForDash(dashId: Long) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Deleting pickup events for dash: $dashId")
            pickupEventDao.deleteEventsForDash(dashId)
        }
    }
}