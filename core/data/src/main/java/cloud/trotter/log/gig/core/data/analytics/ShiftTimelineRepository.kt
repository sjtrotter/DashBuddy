package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.OfferRecordEntity
import cloud.trotter.log.gig.core.database.analytics.PickupRecordEntity
import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.timeline.ShiftTimeline
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineDelivery
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineItem
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineOffer
import cloud.trotter.log.gig.domain.analytics.timeline.TimelinePickup
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** One shift's timeline rows (#1516): read-model only, mapped at the boundary, folded by [ShiftTimeline]. */
@Singleton
class ShiftTimelineRepository @Inject constructor(private val analyticsDao: AnalyticsDao) {

    /** Every offer of the shift, every outcome, by decision time. */
    fun offersForSession(sessionId: String): Flow<List<TimelineOffer>> =
        analyticsDao.offersForSession(sessionId).map { rows -> rows.map { it.toTimeline() } }

    fun pickupsForSession(sessionId: String): Flow<List<TimelinePickup>> =
        analyticsDao.pickupsForSession(sessionId).map { rows -> rows.map { it.toTimeline() } }

    fun deliveriesForSession(sessionId: String): Flow<List<TimelineDelivery>> =
        analyticsDao.deliveriesForSession(sessionId).map { rows -> rows.map { it.toTimeline() } }

    /** The folded timeline; re-emits on every projector commit to any of the three tables. */
    fun timeline(sessionId: String, running: Boolean): Flow<List<TimelineItem>> =
        combine(offersForSession(sessionId), pickupsForSession(sessionId), deliveriesForSession(sessionId)) { o, p, d ->
            ShiftTimeline.fold(o, p, d, running)
        }
}

internal fun OfferRecordEntity.toTimeline() = TimelineOffer(
    eventSequenceId = eventSequenceId,
    offerHash = offerHash,
    outcome = OfferOutcome.fromEventTypeName(outcome),
    decidedAt = decidedAt,
    payAmount = payAmount,
    distanceMiles = distanceMiles,
    merchantName = merchantName,
    offerScope = OfferScope.entries.find { it.name == offerScope },
    quoteBasis = OfferQuoteBasis.entries.find { it.name == quoteBasis },
    linkedJobId = linkedJobId,
)

internal fun PickupRecordEntity.toTimeline() = TimelinePickup(
    eventSequenceId = eventSequenceId,
    jobId = jobId,
    storeName = storeName,
    arrivedAt = arrivedAt,
    confirmedAt = confirmedAt,
)

internal fun DeliveryRecordEntity.toTimeline() = TimelineDelivery(
    eventSequenceId = eventSequenceId,
    jobId = jobId,
    storeName = storeName,
    completedAt = completedAt,
    realizedPay = realizedPay,
    payBasis = payBasis,
    cashTip = cashTip,
    jobOfferHashes = StringListColumn.decode(jobOfferHashes),
    soleOfferHash = soleOfferHash,
)
