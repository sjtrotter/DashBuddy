package cloud.trotter.dashbuddy.ui.bubble.cards

import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE

/**
 * Pure fold from the AppEvent log to the completed-card list for the bubble
 * HUD flow-card stack (#257). One linear pass over the events; closing
 * events (e.g. `OFFER_ACCEPTED`) carry their full phase context via the
 * rich payload classes wired in `EffectMap.kt`, so the fold itself does
 * not need to join other entities.
 *
 * The active (live) card is NOT produced here — it's built directly from
 * the current `AppState` by [BubbleViewModel]. This fold only produces
 * the completed cards above the active one.
 */
object FlowCardMapper {

    fun fold(events: List<AppEvent>): List<FlowCardSnapshot> {
        val completed = mutableListOf<FlowCardSnapshot>()

        // Open card accumulators — one slot per kind. For v1 we assume a
        // single delivery in flight at a time; stacked-order support is
        // tracked separately.
        var openAwaiting: FlowCardSnapshot.Awaiting? = null
        var openPickup: FlowCardSnapshot.Pickup? = null
        var openDelivery: FlowCardSnapshot.Delivery? = null
        var lastDeliveryArrivedAt: Long? = null
        // The accepted offer's economics, carried onto the task cards for the
        // "Running at $/hr" co-hero (#460). v1 tracks a single job in flight; a
        // stacked add-on overwrites (the live card uses the accurate Job.blended).
        var acceptedNetPay: Double? = null
        var acceptedEstMin: Double? = null
        var acceptedDistanceMiles: Double? = null

        for (event in events) {
            when (event.type) {
                AppEventType.DASH_START -> {
                    // Reset stack — flush any half-open cards, start fresh
                    completed.clear()
                    openAwaiting = null
                    openPickup = null
                    openDelivery = null
                    lastDeliveryArrivedAt = null
                    acceptedNetPay = null
                    acceptedEstMin = null
                    acceptedDistanceMiles = null

                    val payload = event.payload as? SessionStartPayload
                    openAwaiting = FlowCardSnapshot.Awaiting(
                        id = "awaiting:${payload?.sessionId ?: event.sessionId}:${event.occurredAt}",
                        phaseStartedAt = payload?.startedAt ?: event.occurredAt,
                        sessionId = payload?.sessionId ?: event.sessionId,
                    )
                }

                AppEventType.OFFER_RECEIVED -> {
                    // Close the Awaiting card at the moment the offer hit
                    // the screen. The Offer card itself is built from the
                    // closing OFFER_ACCEPTED/DECLINED/TIMEOUT event since
                    // that's where the rich evaluation lands. Prefer the
                    // payload's presentedAt; fall back to event occurredAt
                    // for legacy rows where the payload is empty/missing.
                    val payload = event.payload as? OfferReceivedPayload
                    val endedAt = payload?.presentedAt?.takeIf { it > 0 } ?: event.occurredAt
                    openAwaiting?.let {
                        completed.add(it.copy(phaseEndedAt = endedAt))
                        openAwaiting = null
                    }
                }

                AppEventType.OFFER_ACCEPTED,
                AppEventType.OFFER_DECLINED,
                AppEventType.OFFER_TIMEOUT -> {
                    val payload = event.payload as? OfferPayload ?: continue
                    // Defensive close of Awaiting using the offer's presentedAt.
                    // The rule-declared OFFER_RECEIVED log effect currently
                    // doesn't persist to the DB (it only goes to Timber), so
                    // Awaiting would otherwise hang open until DASH_STOP.
                    openAwaiting?.let {
                        completed.add(it.copy(phaseEndedAt = payload.presentedAt))
                        openAwaiting = null
                    }
                    completed.add(
                        FlowCardSnapshot.Offer.from(
                            parsedOffer = payload.parsedOffer,
                            evaluation = payload.evaluation,
                            offerHash = payload.offerHash,
                            phaseStartedAt = payload.presentedAt,
                            phaseEndedAt = payload.decidedAt,
                            outcome = payload.outcome,
                        )
                    )
                    // On accept, capture the offer's economics for the upcoming
                    // task cards' live $/hr co-hero (#460).
                    if (payload.outcome == AppEventType.OFFER_ACCEPTED) {
                        acceptedNetPay = payload.evaluation?.netPayAmount
                        acceptedEstMin = payload.evaluation?.estimatedTimeMinutes
                        acceptedDistanceMiles = payload.evaluation?.distanceMiles ?: payload.parsedOffer.distanceMiles
                    }
                    // Re-open Awaiting if the dasher returned to the
                    // waiting-for-offer state (declined / timeout). Accept
                    // skips this — they're going into pickup.
                    if (payload.outcome == AppEventType.OFFER_DECLINED ||
                        payload.outcome == AppEventType.OFFER_TIMEOUT
                    ) {
                        openAwaiting = FlowCardSnapshot.Awaiting(
                            id = "awaiting:${event.sessionId}:${payload.decidedAt}",
                            phaseStartedAt = payload.decidedAt,
                            sessionId = event.sessionId,
                        )
                    }
                }

                AppEventType.PICKUP_NAV_STARTED -> {
                    val payload = event.payload as? PickupPayload ?: continue
                    // If a pickup is already open and matches this taskId, this
                    // is a follow-up (e.g. store-name resolution) — update.
                    // Otherwise close any stale one and open a new card.
                    val current = openPickup
                    if (current?.taskId == payload.taskId) {
                        openPickup = current.copy(
                            storeName = payload.storeName.ifBlank { current.storeName },
                            itemsRemaining = payload.itemsRemaining ?: current.itemsRemaining,
                            itemsShopped = payload.itemsShopped ?: current.itemsShopped,
                            deadlineMillis = payload.deadlineMillis ?: current.deadlineMillis,
                            activity = payload.activity ?: current.activity,
                        )
                    } else {
                        openPickup?.let { completed.add(it.copy(phaseEndedAt = event.occurredAt)) }
                        openPickup = FlowCardSnapshot.Pickup(
                            phaseStartedAt = payload.phaseStartedAt,
                            taskId = payload.taskId,
                            jobId = payload.jobId,
                            netPay = acceptedNetPay,
                            estMinutes = acceptedEstMin,
                            distanceMiles = acceptedDistanceMiles,
                            storeName = payload.storeName.ifBlank { UNKNOWN_STORE },
                            deadlineMillis = payload.deadlineMillis,
                            itemsRemaining = payload.itemsRemaining,
                            itemsShopped = payload.itemsShopped,
                            activity = payload.activity,
                        )
                    }
                }

                AppEventType.PICKUP_ARRIVED -> {
                    val payload = event.payload as? PickupPayload ?: continue
                    val current = openPickup
                    openPickup = if (current?.taskId == payload.taskId) {
                        current.copy(
                            arrivedAt = payload.arrivedAt ?: event.occurredAt,
                            storeName = payload.storeName.takeIf { it.isNotBlank() } ?: current.storeName,
                        )
                    } else {
                        // No open pickup (probably a recovered session) — synthesize.
                        FlowCardSnapshot.Pickup(
                            phaseStartedAt = payload.phaseStartedAt,
                            taskId = payload.taskId,
                            jobId = payload.jobId,
                            netPay = acceptedNetPay,
                            estMinutes = acceptedEstMin,
                            distanceMiles = acceptedDistanceMiles,
                            storeName = payload.storeName.ifBlank { UNKNOWN_STORE },
                            arrivedAt = payload.arrivedAt ?: event.occurredAt,
                            deadlineMillis = payload.deadlineMillis,
                            itemsRemaining = payload.itemsRemaining,
                            itemsShopped = payload.itemsShopped,
                            activity = payload.activity,
                        )
                    }
                }

                AppEventType.PICKUP_CONFIRMED -> {
                    val payload = event.payload as? PickupPayload ?: continue
                    val current = openPickup
                    val closed = if (current?.taskId == payload.taskId) {
                        current.copy(
                            confirmedAt = payload.confirmedAt ?: event.occurredAt,
                            phaseEndedAt = payload.confirmedAt ?: event.occurredAt,
                        )
                    } else {
                        FlowCardSnapshot.Pickup(
                            phaseStartedAt = payload.phaseStartedAt,
                            phaseEndedAt = payload.confirmedAt ?: event.occurredAt,
                            taskId = payload.taskId,
                            jobId = payload.jobId,
                            netPay = acceptedNetPay,
                            estMinutes = acceptedEstMin,
                            distanceMiles = acceptedDistanceMiles,
                            storeName = payload.storeName.ifBlank { UNKNOWN_STORE },
                            arrivedAt = payload.arrivedAt,
                            confirmedAt = payload.confirmedAt ?: event.occurredAt,
                            deadlineMillis = payload.deadlineMillis,
                            itemsRemaining = payload.itemsRemaining,
                            itemsShopped = payload.itemsShopped,
                            activity = payload.activity,
                        )
                    }
                    completed.add(closed)
                    openPickup = null
                }

                AppEventType.DELIVERY_NAV_STARTED -> {
                    val payload = event.payload as? DeliveryPayload ?: continue
                    // Idempotent — if already open with same taskId, leave it
                    if (openDelivery?.taskId != payload.taskId) {
                        openDelivery?.let { completed.add(it.copy(phaseEndedAt = event.occurredAt)) }
                        openDelivery = FlowCardSnapshot.Delivery(
                            phaseStartedAt = payload.phaseStartedAt,
                            taskId = payload.taskId,
                            jobId = payload.jobId,
                            netPay = acceptedNetPay,
                            estMinutes = acceptedEstMin,
                            distanceMiles = acceptedDistanceMiles,
                            storeName = payload.storeName,
                            customerHash = payload.customerHash,
                            deadlineMillis = payload.deadlineMillis,
                        )
                    }
                }

                AppEventType.DELIVERY_ARRIVED -> {
                    val payload = event.payload as? DeliveryPayload ?: continue
                    val current = openDelivery
                    val closed = if (current?.taskId == payload.taskId) {
                        current.copy(
                            arrivedAt = payload.arrivedAt ?: event.occurredAt,
                            phaseEndedAt = payload.arrivedAt ?: event.occurredAt,
                        )
                    } else {
                        FlowCardSnapshot.Delivery(
                            phaseStartedAt = payload.phaseStartedAt,
                            phaseEndedAt = payload.arrivedAt ?: event.occurredAt,
                            taskId = payload.taskId,
                            jobId = payload.jobId,
                            netPay = acceptedNetPay,
                            estMinutes = acceptedEstMin,
                            distanceMiles = acceptedDistanceMiles,
                            storeName = payload.storeName,
                            customerHash = payload.customerHash,
                            arrivedAt = payload.arrivedAt ?: event.occurredAt,
                            deadlineMillis = payload.deadlineMillis,
                        )
                    }
                    completed.add(closed)
                    openDelivery = null
                    lastDeliveryArrivedAt = payload.arrivedAt ?: event.occurredAt
                }

                AppEventType.DELIVERY_CONFIRMED -> {
                    // Dasher finished the drop-off. Closes the open Delivery
                    // card. Fires before DELIVERY_COMPLETED (which carries the
                    // PostTask pay breakdown). Analogue of PICKUP_CONFIRMED.
                    val payload = event.payload as? DeliveryPayload ?: continue
                    val current = openDelivery
                    val closed = if (current?.taskId == payload.taskId) {
                        current.copy(phaseEndedAt = event.occurredAt)
                    } else {
                        FlowCardSnapshot.Delivery(
                            phaseStartedAt = payload.phaseStartedAt,
                            phaseEndedAt = event.occurredAt,
                            taskId = payload.taskId,
                            jobId = payload.jobId,
                            netPay = acceptedNetPay,
                            estMinutes = acceptedEstMin,
                            distanceMiles = acceptedDistanceMiles,
                            storeName = payload.storeName,
                            customerHash = payload.customerHash,
                            arrivedAt = payload.arrivedAt,
                            deadlineMillis = payload.deadlineMillis,
                        )
                    }
                    completed.add(closed)
                    openDelivery = null
                    lastDeliveryArrivedAt = lastDeliveryArrivedAt ?: event.occurredAt
                }

                AppEventType.DELIVERY_COMPLETED -> {
                    val payload = event.payload as? DeliveryPayload ?: continue
                    // PostTask card spans from delivery arrival (start of the
                    // receipt screen) to the dismiss-receipt moment that
                    // fired DELIVERY_COMPLETED.
                    val start = lastDeliveryArrivedAt ?: payload.phaseStartedAt
                    completed.add(
                        FlowCardSnapshot.PostTask(
                            phaseStartedAt = start,
                            phaseEndedAt = payload.completedAt ?: event.occurredAt,
                            jobId = payload.jobId,
                            taskId = payload.taskId,
                            storeName = payload.storeName,
                            totalPay = payload.totalPay ?: 0.0,
                            parsedPay = payload.parsedPay,
                            sessionEarningsAtCompletion = payload.sessionEarningsAtCompletion,
                        )
                    )
                    lastDeliveryArrivedAt = null
                    // Re-open Awaiting — dasher dismissed the receipt and
                    // is back to waiting for the next offer. (For stacked
                    // deliveries where the dasher goes straight to the next
                    // dropoff, this Awaiting would transiently close on the
                    // next OFFER_RECEIVED — acceptable until stacked-delivery
                    // intermediate-leg detection lands as a separate fix.)
                    val awaitingStart = payload.completedAt ?: event.occurredAt
                    openAwaiting = FlowCardSnapshot.Awaiting(
                        id = "awaiting:${event.sessionId}:${awaitingStart}",
                        phaseStartedAt = awaitingStart,
                        sessionId = event.sessionId,
                    )
                }

                AppEventType.DASH_STOP -> {
                    val payload = event.payload as? SessionStopPayload
                    val endedAt = payload?.endedAt ?: event.occurredAt
                    // Flush any half-open cards
                    openAwaiting?.let { completed.add(it.copy(phaseEndedAt = endedAt)) }
                    openPickup?.let { completed.add(it.copy(phaseEndedAt = endedAt)) }
                    openDelivery?.let { completed.add(it.copy(phaseEndedAt = endedAt)) }
                    openAwaiting = null
                    openPickup = null
                    openDelivery = null
                    lastDeliveryArrivedAt = null
                }

                else -> {
                    // Other event types (ZONE_SWITCH, DASH_PAUSED, NOTIFICATION_*,
                    // SCREEN_VIEWED, ERROR_OCCURRED) don't open or close cards.
                }
            }
        }

        // Dedup by card id, keeping the last (most-complete) occurrence.
        // The card id is the LazyColumn key, which MUST be unique or Compose
        // throws a fatal IllegalArgumentException ("Key ... was already used").
        // Several real event orderings emit the same id twice:
        //   • DELIVERY_ARRIVED *and* DELIVERY_CONFIRMED for one dropoff — the
        //     mapper once assumed these were mutually exclusive, but
        //     arrival-bearing dropoffs (photo / PIN / hand-it-to-customer /
        //     alcohol ID-scan) fire both. Field DB 2026-06-03 (taskId
        //     c0041f37: ARRIVED 17:59:23 → CONFIRMED 17:59:33 → crash 17:59:34).
        //   • A dropoff that double-fires DELIVERY_CONFIRMED (same DB, 22:00).
        //   • The same offerHash decided twice / a job that double-completes.
        // associateBy keeps each id at its first-seen position with the last
        // value — chronological order preserved, newest content wins.
        return completed.associateBy { it.id }.values.toList()
    }
}
