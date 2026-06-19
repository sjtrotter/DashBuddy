package cloud.trotter.dashbuddy.domain.model.cards

import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
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
        /** Evaluator quality level ("Great offer", "Bad offer", …) for the verdict chip. */
        val qualityLevel: OfferQuality? = null,
        /** Offer + order badge enum names (e.g. HIGH_PAYING, RED_CARD, ALCOHOL) for the pill row. */
        val badges: List<String> = emptyList(),
        /** When the DoorDash offer countdown expires (presentedAt + initialCountdown). Null if unparsed. */
        val expiresAt: Long? = null,
        /** Initial offer countdown in seconds — the denominator for the live expiry progress bar. */
        val countdownSeconds: Int? = null,
        val outcome: AppEventType? = null,
    ) : FlowCardSnapshot() {
        override val id: String get() = "offer:$offerHash"

        companion object {
            /**
             * Single owner (SSOT) for assembling an [Offer] card from a
             * [ParsedOffer] + its [OfferEvaluation]. Both the live builder
             * (from `AppState`) and the event-log fold call this so the
             * badge list, store-name derivation, and the parsed/evaluation
             * field copy can never drift apart again (the #461 SHOP-parity
             * bug was exactly that divergence). Call-site-specific framing
             * — `phaseStartedAt`/`phaseEndedAt`, `offerHash`, the live
             * countdown anchors, and the decided `outcome` — stays a
             * parameter; only the shared assembly lives here.
             */
            fun from(
                parsedOffer: ParsedOffer,
                evaluation: OfferEvaluation?,
                offerHash: String,
                phaseStartedAt: Long,
                phaseEndedAt: Long? = null,
                expiresAt: Long? = null,
                countdownSeconds: Int? = null,
                outcome: AppEventType? = null,
            ): Offer = Offer(
                phaseStartedAt = phaseStartedAt,
                phaseEndedAt = phaseEndedAt,
                offerHash = offerHash,
                payAmount = parsedOffer.payAmount,
                distanceMiles = parsedOffer.distanceMiles,
                itemCount = parsedOffer.itemCount,
                storeNames = parsedOffer.orders.map { it.storeName }.distinct(),
                evaluationScore = evaluation?.score,
                evaluationAction = evaluation?.action?.name,
                netPayAmount = evaluation?.netPayAmount,
                dollarsPerMile = evaluation?.dollarsPerMile,
                dollarsPerHour = evaluation?.dollarsPerHour,
                qualityLevel = evaluation?.qualityLevel,
                badges = badgesOf(parsedOffer),
                expiresAt = expiresAt,
                countdownSeconds = countdownSeconds,
                outcome = outcome,
            )

            /**
             * The offer + order badge enum names for the pill row, plus a
             * synthetic `"SHOP"` marker when any order is a Shop & Deliver
             * (#461) — `orderType` is known at offer time, so the card can be
             * typed at a glance.
             */
            private fun badgesOf(parsedOffer: ParsedOffer): List<String> =
                (parsedOffer.badges.map { it.name } +
                    parsedOffer.orders.flatMap { it.badges }.map { it.name } +
                    if (parsedOffer.orders.any { it.orderType == OrderType.SHOP_FOR_ITEMS }) {
                        listOf("SHOP")
                    } else {
                        emptyList()
                    }).distinct()
        }
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
        val itemsRemaining: Int? = null,
        val itemsShopped: Int? = null,
        val activity: String? = null,
        /** The job's blended net pay — the numerator for the live "Running at $/hr"
         *  co-hero (#460). Null until an accepted offer's economics are known. */
        val netPay: Double? = null,
        /** The job's blended estimated minutes — the $/hr denominator (erodes past
         *  the deadline, the drop-it signal). */
        val estMinutes: Double? = null,
        /** The job's blended quoted distance — the denominator for the fixed "$/mi"
         *  efficiency shown beside the live $/hr (#503 deliverable 2). Null → "—". */
        val distanceMiles: Double? = null,
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
        /** Blended net pay for the live "Running at $/hr" co-hero (#460). */
        val netPay: Double? = null,
        /** Blended estimated minutes — the $/hr denominator (erodes past deadline). */
        val estMinutes: Double? = null,
        /** Blended quoted distance — the denominator for the fixed "$/mi" efficiency
         *  shown beside the live $/hr (#503 deliverable 2). Null → "—". */
        val distanceMiles: Double? = null,
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

        /**
         * Assemble the stack from the folded [completed] history + the current
         * [active] card, dropping any frozen completed card whose id matches
         * the active one (#458). On an arrival-bearing dropoff the mapper
         * closes the Delivery into `completed` on DELIVERY_ARRIVED while the
         * live builder still emits an active Delivery for the same task — same
         * id `delivery:<taskId>` — which would render as TWO cards during the
         * at-door window (no crash: #297's `live:` key prefix holds). Suppress
         * the frozen twin so exactly one card shows; it resolves on
         * DELIVERY_COMPLETED anyway when the active card becomes a PostTask.
         */
        fun of(completed: List<FlowCardSnapshot>, active: FlowCardSnapshot?): CardStack =
            CardStack(
                completed = if (active == null) completed
                else completed.filterNot { it.id == active.id },
                active = active,
            )
    }
}
