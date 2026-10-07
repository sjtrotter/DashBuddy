package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.state.Flow

/** Payload builders shared by the pure folds and Room projector tests; no persistence or assertions. */
object AnalyticsEventFixtures {
    fun evaluation(
        net: Double,
        dist: Double,
        opCpm: Double,
        fuelPerMile: Double = 0.0,
        nonFuelPerMile: Double = opCpm - fuelPerMile,
        payAmount: Double = net + dist * opCpm,
        dollarsPerMile: Double = if (dist > 0) net / dist else 0.0,
    ) = OfferEvaluation(
        action = OfferAction.ACCEPT,
        score = 80.0,
        qualityLevel = OfferQuality.GOOD,
        payAmount = payAmount,
        // Route totals: the fold divides by distanceMiles to recover the per-mile split.
        fuelCostEstimate = fuelPerMile * dist,
        nonFuelCostEstimate = nonFuelPerMile * dist,
        operatingCostPerMile = opCpm,
        netPayAmount = net,
        distanceMiles = dist,
        dollarsPerMile = dollarsPerMile,
        dollarsPerHour = 20.0,
        estimatedTimeMinutes = 15.0,
        itemCount = 1.0,
        merchantName = "StoreX",
    )

    fun acceptedOffer(
        at: Long,
        evaluation: OfferEvaluation?,
        hash: String = "h1",
        presentedAt: Long = at - 30,
    ) = OfferPayload(
        offerHash = hash,
        parsedOffer = ParsedOffer(offerHash = hash, payAmount = 12.0, distanceMiles = 3.0, itemCount = 1),
        evaluation = evaluation,
        outcome = AppEventType.OFFER_ACCEPTED,
        presentedAt = presentedAt,
        decidedAt = at,
        returnFlow = Flow.Idle,
    )
}
