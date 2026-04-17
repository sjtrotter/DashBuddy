package cloud.trotter.dashbuddy.core.data.event

import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppEventRepo @Inject constructor(
    private val dao: AppEventDao
) {

    suspend fun insert(event: AppEventEntity): Long {
        return dao.insert(event)
    }

    fun getAllEvents(): Flow<List<AppEventEntity>> {
        return dao.getAllEvents()
    }

    fun getEventsForDash(dashId: String): Flow<List<AppEventEntity>> {
        return dao.getEventsForDash(dashId)
    }

    fun getEventsByType(type: AppEventType): Flow<List<AppEventEntity>> {
        return dao.getEventsByType(type)
    }

    fun getEventsInTimeRange(startTime: Long, endTime: Long): Flow<List<AppEventEntity>> {
        return dao.getEventsInTimeRange(startTime, endTime)
    }
}