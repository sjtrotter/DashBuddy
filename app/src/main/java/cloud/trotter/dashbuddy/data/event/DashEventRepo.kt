package cloud.trotter.dashbuddy.data.event

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import cloud.trotter.dashbuddy.log.Logger as Log

class DashEventRepo(private val dashEventDao: DashEventDao) {
    private val tag = "DashEventRepo"

    suspend fun insert(event: DashEventEntity): Long {
        return withContext(Dispatchers.IO) {
            Log.i(tag, "Logging Dash Event: ${event.type} at ${event.timestamp}")
            dashEventDao.insert(event)
        }
    }

    suspend fun getLatestEvent(): DashEventEntity? {
        return withContext(Dispatchers.IO) {
            dashEventDao.getLatestEvent()
        }
    }

    fun getAllEventsFlow(): Flow<List<DashEventEntity>> {
        return dashEventDao.getAllEventsFlow()
    }
}