package cloud.trotter.dashbuddy.ui.bubble.cards

import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import timber.log.Timber

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

    private val gson = Gson()

    fun fold(events: List<AppEventEntity>): List<FlowCardSnapshot> {
        val completed = mutableListOf<FlowCardSnapshot>()

        // Open card accumulators — one slot per kind. For v1 we assume a
        // single delivery in flight at a time; stacked-order support is
        // tracked separately.
        var openAwaiting: FlowCardSnapshot.Awaiting? = null
        var openPickup: FlowCardSnapshot.Pickup? = null
        var openDelivery: FlowCardSnapshot.Delivery? = null
        var lastDeliveryArrivedAt: Long? = null

        for (event in events) {
            when (event.eventType) {
                AppEventType.DASH_START -> {
                    // Reset stack — flush any half-open cards, start fresh
                    completed.clear()
                    openAwaiting = null
                    openPickup = null
                    openDelivery = null
                    lastDeliveryArrivedAt = null

                    val payload = decode(event, SessionStartPayload::class.java)
                    openAwaiting = FlowCardSnapshot.Awaiting(
                        id = "awaiting:${payload?.sessionId ?: event.aggregateId}:${event.occurredAt}",
                        phaseStartedAt = payload?.startedAt ?: event.occurredAt,
                        sessionId = payload?.sessionId ?: event.aggregateId,
                    )
                }

                AppEventType.OFFER_RECEIVED -> {
                    // Close the Awaiting card at the moment the offer hit
                    // the screen. The Offer card itself is built from the
                    // closing OFFER_ACCEPTED/DECLINED/TIMEOUT event since
                    // that's where the rich evaluation lands. Prefer the
                    // payload's presentedAt; fall back to event occurredAt
                    // for legacy rows where the payload is empty/missing.
                    val payload = decode(event, OfferReceivedPayload::class.java)
                    val endedAt = payload?.presentedAt?.takeIf { it > 0 } ?: event.occurredAt
                    openAwaiting?.let {
                        completed.add(it.copy(phaseEndedAt = endedAt))
                        openAwaiting = null
                    }
                }

                AppEventType.OFFER_ACCEPTED,
                AppEventType.OFFER_DECLINED,
                AppEventType.OFFER_TIMEOUT -> {
                    val payload = decode(event, OfferPayload::class.java) ?: continue
                    // Defensive close of Awaiting using the offer's presentedAt.
                    // The rule-declared OFFER_RECEIVED log effect currently
                    // doesn't persist to the DB (it only goes to Timber), so
                    // Awaiting would otherwise hang open until DASH_STOP.
                    openAwaiting?.let {
                        completed.add(it.copy(phaseEndedAt = payload.presentedAt))
                        openAwaiting = null
                    }
                    val storeNames = payload.parsedOffer.orders
                        .map { it.storeName }
                        .distinct()
                    completed.add(
                        FlowCardSnapshot.Offer(
                            phaseStartedAt = payload.presentedAt,
                            phaseEndedAt = payload.decidedAt,
                            offerHash = payload.offerHash,
                            payAmount = payload.parsedOffer.payAmount,
                            distanceMiles = payload.parsedOffer.distanceMiles,
                            itemCount = payload.parsedOffer.itemCount,
                            storeNames = storeNames,
                            evaluationScore = payload.evaluation?.score,
                            evaluationAction = payload.evaluation?.action?.name,
                            netPayAmount = payload.evaluation?.netPayAmount,
                            dollarsPerMile = payload.evaluation?.dollarsPerMile,
                            dollarsPerHour = payload.evaluation?.dollarsPerHour,
                            outcome = payload.outcome,
                        )
                    )
                    // Re-open Awaiting if the dasher returned to the
                    // waiting-for-offer state (declined / timeout). Accept
                    // skips this — they're going into pickup.
                    if (payload.outcome == AppEventType.OFFER_DECLINED ||
                        payload.outcome == AppEventType.OFFER_TIMEOUT
                    ) {
                        openAwaiting = FlowCardSnapshot.Awaiting(
                            id = "awaiting:${event.aggregateId}:${payload.decidedAt}",
                            phaseStartedAt = payload.decidedAt,
                            sessionId = event.aggregateId,
                        )
                    }
                }

                AppEventType.PICKUP_NAV_STARTED -> {
                    val payload = decode(event, PickupPayload::class.java) ?: continue
                    // If a pickup is already open and matches this taskId, this
                    // is a follow-up (e.g. store-name resolution) — update.
                    // Otherwise close any stale one and open a new card.
                    val current = openPickup
                    if (current?.taskId == payload.taskId) {
                        openPickup = current.copy(
                            storeName = payload.storeName,
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
                            storeName = payload.storeName,
                            deadlineMillis = payload.deadlineMillis,
                            itemsRemaining = payload.itemsRemaining,
                            itemsShopped = payload.itemsShopped,
                            activity = payload.activity,
                        )
                    }
                }

                AppEventType.PICKUP_ARRIVED -> {
                    val payload = decode(event, PickupPayload::class.java) ?: continue
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
                            storeName = payload.storeName,
                            arrivedAt = payload.arrivedAt ?: event.occurredAt,
                            deadlineMillis = payload.deadlineMillis,
                            itemsRemaining = payload.itemsRemaining,
                            itemsShopped = payload.itemsShopped,
                            activity = payload.activity,
                        )
                    }
                }

                AppEventType.PICKUP_CONFIRMED -> {
                    val payload = decode(event, PickupPayload::class.java) ?: continue
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
                            storeName = payload.storeName,
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
                    val payload = decode(event, DeliveryPayload::class.java) ?: continue
                    // Idempotent — if already open with same taskId, leave it
                    if (openDelivery?.taskId != payload.taskId) {
                        openDelivery?.let { completed.add(it.copy(phaseEndedAt = event.occurredAt)) }
                        openDelivery = FlowCardSnapshot.Delivery(
                            phaseStartedAt = payload.phaseStartedAt,
                            taskId = payload.taskId,
                            jobId = payload.jobId,
                            storeName = payload.storeName,
                            customerHash = payload.customerHash,
                            deadlineMillis = payload.deadlineMillis,
                        )
                    }
                }

                AppEventType.DELIVERY_ARRIVED -> {
                    val payload = decode(event, DeliveryPayload::class.java) ?: continue
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
                    val payload = decode(event, DeliveryPayload::class.java) ?: continue
                    val current = openDelivery
                    val closed = if (current?.taskId == payload.taskId) {
                        current.copy(phaseEndedAt = event.occurredAt)
                    } else {
                        FlowCardSnapshot.Delivery(
                            phaseStartedAt = payload.phaseStartedAt,
                            phaseEndedAt = event.occurredAt,
                            taskId = payload.taskId,
                            jobId = payload.jobId,
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
                    val payload = decode(event, DeliveryPayload::class.java) ?: continue
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
                        id = "awaiting:${event.aggregateId}:${awaitingStart}",
                        phaseStartedAt = awaitingStart,
                        sessionId = event.aggregateId,
                    )
                }

                AppEventType.DASH_STOP -> {
                    val payload = decode(event, SessionStopPayload::class.java)
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

        return completed
    }

    private fun <T> decode(event: AppEventEntity, klass: Class<T>): T? = try {
        gson.fromJson(event.eventPayload, klass)
    } catch (e: JsonSyntaxException) {
        Timber.w(e, "FlowCardMapper: failed to decode %s for %s", klass.simpleName, event.eventType)
        null
    }
}
