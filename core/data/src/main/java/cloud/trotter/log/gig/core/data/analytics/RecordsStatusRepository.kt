package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.event.AppEventDao
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The one owner of [RecordsStatus], the value every look-back screen reads before it shows figures.
 *
 * REBUILDING comes from the projector's in-memory [AnalyticsProjector.rebuilding] flag; UPDATING from
 * the stored watermark against the event log's latest id (both Room flows, so the status moves as the
 * projector commits). The rule itself is the pure [RecordsStatus.of].
 */
@Singleton
class RecordsStatusRepository internal constructor(
    rebuilding: Flow<Boolean>,
    watermark: Flow<Long?>,
    latestEventId: Flow<Long?>,
) {
    @Inject
    constructor(
        projector: AnalyticsProjector,
        analyticsRepository: AnalyticsRepository,
        appEventDao: AppEventDao,
    ) : this(projector.rebuilding, analyticsRepository.projectionWatermark, appEventDao.maxSequenceId())

    val status: Flow<RecordsStatus> =
        combine(rebuilding, watermark, latestEventId, RecordsStatus::of).distinctUntilChanged()
}
