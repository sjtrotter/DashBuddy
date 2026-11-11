package cloud.trotter.log.gig.domain.analytics.timeline

import cloud.trotter.log.gig.domain.analytics.OfferOutcome

/**
 * One row of the shift timeline (#1516), in display order, keyed for a `LazyColumn`. The model holds
 * recorded figures only: no estimate, score, rate or minutes field.
 */
sealed interface TimelineItem {
    val key: String
}

/**
 * A job: its accepted offer(s) and its deliveries. [key] is `offer:<seq>` of the job's first accepted offer, so
 * it holds from the accept through the pickup and delivery; `job:<jobId>` for a job with no joined offer.
 */
data class JobCard(
    override val key: String,
    val jobId: String?,
    /** The store text as recorded ("A & B"); the offer's, else the deliveries' or pickups' stores. */
    val stores: String?,
    /** The first accepted offer's decision time; null for a card with no offer. */
    val firstOfferAt: Long?,
    /** Σ of the deliveries' pay without cash; null when no delivery is priced. */
    val paidTotal: Double?,
    /** True when [paidTotal] includes an estimated delivery pay. */
    val paidTotalEstimated: Boolean,
    /** The job's first offer; null when the job has no offer or only add-on quotes. */
    val offerLine: OfferLine?,
    val addOns: List<AddOnLine>,
    val deliveries: List<DeliveryLine>,
    /** Σ of the deliveries' cash tips; null when none. Never inside [paidTotal]. */
    val cashTips: Double?,
    val state: JobState,
    /** The accepted offers' sequence ids, so a link can land on this card. */
    val offerEventSequenceIds: List<Long>,
    /** The accepted offers with no recorded unassign and no resolution, in decision order. */
    val openOffers: List<OpenOffer> = emptyList(),
    /** The first recorded unassign on the job ([JobState.UNASSIGNED]); null when none. */
    val unassignedAt: Long? = null,
    /** The accepted offers the dasher marked unassigned, the targets of the card's Undo. */
    val markedOfferSeqs: List<Long> = emptyList(),
    /** #1119 S2: the card's accepted offers that tied on a job before linking, by event sequence id. */
    val tiedOffers: Map<Long, List<String>> = emptyMap(),
    /** The recorded milestones for the job's sheet, in time order; a milestone not recorded is absent. */
    val milestones: List<Milestone>,
) : TimelineItem

/** A run of consecutive declined, expired and not-taken offers; collapsed by default. [key] is `run:<firstSeq>`. */
data class PassedRun(
    override val key: String,
    val declined: Int,
    val expired: Int,
    /** #1119 S2: offers still in the pool at shift end ([OfferOutcome.NOT_TAKEN]). */
    val notTaken: Int,
    val from: Long,
    val to: Long,
    val offers: List<PassedOffer>,
) : TimelineItem

data class PassedOffer(
    val eventSequenceId: Long,
    val at: Long,
    val stores: String?,
    val pay: Double?,
    val miles: Double?,
    /** [OfferOutcome.DECLINED], [OfferOutcome.TIMED_OUT] or [OfferOutcome.NOT_TAKEN]. */
    val outcome: OfferOutcome,
    /** #1119 S2: the jobs this offer tied on at its store; empty when none. */
    val ambiguousJobIds: List<String> = emptyList(),
)

data class OfferLine(val pay: Double?, val miles: Double?)

/** A second or later accepted offer on a job. */
sealed interface AddOnLine {
    val eventSequenceId: Long

    /** An INCREMENTAL quote: the figures are what the add-on adds. */
    data class Incremental(override val eventSequenceId: Long, val deltaPay: Double?, val deltaMiles: Double?) : AddOnLine

    /** A TOTAL-basis add-on: the figures are the job's new total. */
    data class NewTotal(override val eventSequenceId: Long, val pay: Double?, val miles: Double?) : AddOnLine

    /** An offer whose scope and basis were not recorded (folded before projector 18), shown as another offer. */
    data class Offered(override val eventSequenceId: Long, val pay: Double?, val miles: Double?) : AddOnLine
}

data class DeliveryLine(
    val deliveryEventSequenceId: Long,
    val at: Long,
    val store: String?,
    /** Null when no pay was found. */
    val pay: Double?,
    val source: PaySource,
    val estimated: Boolean,
    val cashTip: Double?,
)

/** An accepted offer on a card that nothing accounts for yet: what the "What happened" sheet asks about. */
data class OpenOffer(
    val eventSequenceId: Long,
    val decidedAt: Long,
    val pay: Double?,
    /** The store text as recorded. */
    val stores: String?,
)

enum class JobState {
    NORMAL,
    /** The shift is running and the job has no delivery yet. */
    ACCEPTED_NO_DELIVERY_YET,
    /** The shift ended, the job has no delivery and an accepted offer is still open. */
    NO_DELIVERY,
    /** No delivery, and a recorded unassign (or the app's store check) accounts for the offers. */
    UNASSIGNED,
    /** No delivery, and the dasher marked the offers unassigned. */
    MARKED_UNASSIGNED,
}

/** In display order for equal times. */
enum class MilestoneKind { ACCEPTED_OFFERED, AT_STORE, PICKED_UP, AT_CUSTOMER, DELIVERED }

/**
 * One line of the job sheet. ACCEPTED_OFFERED carries [offer] or [addOn]; AT_STORE and PICKED_UP carry
 * [store]; AT_CUSTOMER and DELIVERED carry [delivery] (which drop; DELIVERED shows its pay and source).
 */
data class Milestone(
    val kind: MilestoneKind,
    val at: Long,
    val offer: OfferLine? = null,
    val addOn: AddOnLine? = null,
    val store: String? = null,
    val delivery: DeliveryLine? = null,
)
