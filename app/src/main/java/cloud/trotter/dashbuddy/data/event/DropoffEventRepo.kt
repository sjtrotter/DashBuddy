package cloud.trotter.dashbuddy.data.event

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import cloud.trotter.dashbuddy.log.Logger as Log

class DropoffEventRepo(private val dropoffEventDao: DropoffEventDao) {
    private val tag = "DropoffEventRepo"

    suspend fun insert(event: DropoffEventEntity): Long {
        return withContext(Dispatchers.IO) {
            Log.v(tag, "Inserting dropoff event: ${event.customerNameHash} - ${event.status}")
            dropoffEventDao.insert(event)
        }
    }

    suspend fun getLatestEvent(): DropoffEventEntity? {
        return withContext(Dispatchers.IO) {
            dropoffEventDao.getLatestEvent()
        }
    }

    fun getEventsForDashFlow(dashId: Long): Flow<List<DropoffEventEntity>> {
        return dropoffEventDao.getEventsForDashFlow(dashId)
    }

    suspend fun deleteEventsForDash(dashId: Long) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Deleting dropoff events for dash: $dashId")
            dropoffEventDao.deleteEventsForDash(dashId)
        }
    }
}