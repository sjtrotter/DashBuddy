package cloud.trotter.dashbuddy.data.event

import kotlinx.coroutines.flow.Flow

// No @Singleton annotation needed yet
class AppEventRepo(private val appEventDao: AppEventDao) { // No @Inject constructor

    suspend fun insert(event: AppEventEntity): Long {
        return appEventDao.insert(event)
    }

    fun getAllEvents(): Flow<List<AppEventEntity>> {
        return appEventDao.getAllEvents()
    }

    fun getEventsForDash(dashId: String): Flow<List<AppEventEntity>> {
        return appEventDao.getEventsForDash(dashId)
    }

    fun getEventsByType(type: AppEventType): Flow<List<AppEventEntity>> {
        return appEventDao.getEventsByType(type)
    }

    fun getEventsInTimeRange(startTime: Long, endTime: Long): Flow<List<AppEventEntity>> {
        return appEventDao.getEventsInTimeRange(startTime, endTime)
    }
}