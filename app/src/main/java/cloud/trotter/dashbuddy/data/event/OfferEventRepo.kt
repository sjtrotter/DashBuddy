package cloud.trotter.dashbuddy.data.event

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import cloud.trotter.dashbuddy.log.Logger as Log

class OfferEventRepo(private val offerEventDao: OfferEventDao) {
    private val tag = "OfferEventRepo"

    suspend fun insert(event: OfferEventEntity): Long {
        return withContext(Dispatchers.IO) {
            Log.v(
                tag,
                "Inserting offer event: Hash ${event.offerHash.take(8)}... - $${event.payAmount}"
            )
            offerEventDao.insert(event)
        }
    }

    fun getEventsForDashFlow(dashId: Long): Flow<List<OfferEventEntity>> {
        return offerEventDao.getEventsForDashFlow(dashId)
    }

    suspend fun deleteEventsForDash(dashId: Long) {
        withContext(Dispatchers.IO) {
            Log.d(tag, "Deleting offer events for dash: $dashId")
            offerEventDao.deleteEventsForDash(dashId)
        }
    }
}