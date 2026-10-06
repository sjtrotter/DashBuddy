package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.TimeConstantDao
import cloud.trotter.dashbuddy.domain.evaluation.LearnedTimeConstants
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantObservation
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstants
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Read-model-only sibling; never decodes events or consults live economics/state. */
@Singleton
class TimeConstantRepository @Inject constructor(private val dao: TimeConstantDao) {
    val learnedTimeConstants: Flow<Map<Platform, LearnedTimeConstants>> =
        dao.observations(AppEventType.OFFER_ACCEPTED.name).map { rows ->
            TimeConstants.estimate(rows.map { row ->
                TimeConstantObservation(
                    platform = Platform.fromWire(row.platform) ?: Platform.Unknown,
                    eventSequenceId = row.eventSequenceId,
                    phaseStartedAt = row.phaseStartedAt,
                    arrivedAt = row.arrivedAt,
                    completedAt = row.completedAt,
                    sessionId = row.sessionId,
                    sessionAssigned = row.sessionAssigned,
                    payBasis = row.payBasis,
                    originalPayBasis = row.originalPayBasis,
                    realizedMinutes = row.realizedMinutes,
                    milesToStore = row.milesToStore,
                    milesToDropoff = row.milesToDropoff,
                    odometerAtArrival = row.odometerAtArrival,
                    pickupOdometerAtConfirmation = row.pickupOdometerAtConfirmation,
                    jobOfferCount = row.jobOfferCount,
                    soleOfferHash = row.soleOfferHash,
                    orderCount = row.orderCount,
                    orderCountProven = row.orderCountProven,
                    isShop = row.isShop,
                    offerOutcomeResolved = row.offerOutcomeResolved,
                    deliveryCount = row.deliveryCount,
                    pickupCount = row.pickupCount,
                    acceptedOfferCount = row.acceptedOfferCount,
                    matchingOfferCount = row.matchingOfferCount,
                    offerDecidedAt = row.offerDecidedAt,
                    pickupPhaseStartedAt = row.pickupPhaseStartedAt,
                    pickupArrivedAt = row.pickupArrivedAt,
                    pickupConfirmedAt = row.pickupConfirmedAt,
                    pickupActivity = row.pickupActivity,
                )
            })
        }
}
