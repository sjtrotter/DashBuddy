package cloud.trotter.dashbuddy.domain.model.cards

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay

/**
 * A single card in the bubble HUD's flow-card stack (#257).
 *
 * Cards are phase-grouped — five kinds covering one delivery cycle plus
 * the Awaiting period that precedes it:
 *   Awaiting → Offer → Pickup → Delivery → PostTask
 *
 * Each subtype carries the frozen view-state needed to render the card body
 * without any reference to live `AppState`. The currently-active phase is
 * the snapshot whose [phaseEndedAt] is null; it's rebuilt each emission
 * from the focused `PlatformRegion` and uses `rememberNow()` to tick.
 * Completed cards have a non-null [phaseEndedAt] and render statically.
 */
sealed class FlowCardSnapshot {

    /** Stable identifier — used as the LazyColumn item key. */
    abstract val id: String

    /** When this phase opened. */
    abstract val phaseStartedAt: Long

    /** When this phase closed; null if the card is still live. */
    abstract val phaseEndedAt: Long?

    /** True when [phaseEndedAt] is non-null. */
    val isCompleted: Boolean get() = phaseEndedAt != null

    /**
     * The Awaiting card — appears once at the top of each dash session. Live
     * while the dasher is Online and no offer is on screen. Frozen when the
     * first OFFER_RECEIVED arrives.
     */
    data class Awaiting(
        override val id: String,
        override val phaseStartedAt: Long,
        override val phaseEndedAt: Long? = null,
        val sessionId: String? = null,
    ) : FlowCardSnapshot()

    /**
     * One Offer card per offer presented. Live while the offer is on screen;
     * frozen when an OFFER_ACCEPTED / OFFER_DECLINED / OFFER_TIMEOUT event
     * with the same `offerHash` is observed.
     */
    data class Offer(
        override val phaseStartedAt: Long,
        override val phaseEndedAt: Long? = null,
        val offerHash: String,
        val payAmount: Double? = null,
        val distanceMiles: Double? = null,
        val itemCount: Int = 1,
        val storeNames: List<String> = emptyList(),
        val evaluationScore: Double? = null,
        val evaluationAction: String? = null,
        val netPayAmount: Double? = null,
        val dollarsPerMile: Double? = null,
        val dollarsPerHour: Double? = null,
        val outcome: AppEventType? = null,
    ) : FlowCardSnapshot() {
        override val id: String get() = "offer:$offerHash"
    }

    /**
     * One Pickup card per task with phase=PICKUP. Covers both the nav and
     * arrival sub-states. Live while the dasher is en route to / at the
     * store. Frozen when the task transitions to phase=DROPOFF
     * (PICKUP_CONFIRMED).
     */
    data class Pickup(
        override val phaseStartedAt: Long,
        override val phaseEndedAt: Long? = null,
        val taskId: String,
        val jobId: String,
        val storeName: String,
        val arrivedAt: Long? = null,
        val confirmedAt: Long? = null,
        val deadlineMillis: Long? = null,
        val itemCount: Int? = null,
        val activity: String? = null,
    ) : FlowCardSnapshot() {
        override val id: String get() = "pickup:$taskId"
    }

    /**
     * One Delivery card per task with phase=DROPOFF, covering nav + arrival
     * sub-states. Live while the dasher is driving to / at the customer.
     * Frozen on DELIVERY_ARRIVED (so the card's "closing" content is the
     * moment the package was delivered; receipt details live on the
     * subsequent PostTask card).
     */
    data class Delivery(
        override val phaseStartedAt: Long,
        override val phaseEndedAt: Long? = null,
        val taskId: String,
        val jobId: String,
        val storeName: String? = null,
        val customerHash: String? = null,
        val arrivedAt: Long? = null,
        val deadlineMillis: Long? = null,
    ) : FlowCardSnapshot() {
        override val id: String get() = "delivery:$taskId"
    }

    /**
     * One PostTask card per delivery — the receipt / expanded-pay screen.
     * Live while the PostTask flow is showing; frozen when the flow returns
     * to Idle or moves to a new offer.
     */
    data class PostTask(
        override val phaseStartedAt: Long,
        override val phaseEndedAt: Long? = null,
        val jobId: String,
        val taskId: String? = null,
        val storeName: String? = null,
        val totalPay: Double = 0.0,
        val parsedPay: ParsedPay? = null,
        val sessionEarningsAtCompletion: Double? = null,
    ) : FlowCardSnapshot() {
        override val id: String get() = "posttask:$jobId"
    }
}

/**
 * The full bubble HUD card stack at a point in time. Completed cards are
 * rendered statically; [active] (if present) is the focused live card,
 * always at the bottom of the stack, auto-expanded, with a 1Hz ticker.
 */
data class CardStack(
    val completed: List<FlowCardSnapshot> = emptyList(),
    val active: FlowCardSnapshot? = null,
) {
    val isEmpty: Boolean get() = completed.isEmpty() && active == null

    companion object {
        val Empty = CardStack()
    }
}
