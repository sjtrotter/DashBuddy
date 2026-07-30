package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * How a presented offer closed (#975 / brief §7.4) — the typed form of the `offer_records.outcome`
 * column, which stores an [AppEventType] name. A data type, not a stringly-typed value (Principle 4),
 * and the **one owner** of the wire↔display mapping: the filter chip, the row's status pill, and the
 * SQL predicate all resolve through here rather than each spelling `"OFFER_DECLINED"` (Principle 5).
 *
 * The projector writes `AppEventType.name`, so [eventTypeName] derives from the enum itself — there
 * is no literal to drift.
 */
enum class OfferOutcome(val eventType: AppEventType) {
    ACCEPTED(AppEventType.OFFER_ACCEPTED),
    DECLINED(AppEventType.OFFER_DECLINED),
    TIMED_OUT(AppEventType.OFFER_TIMEOUT),
    ;

    /** The stored `offer_records.outcome` value for this outcome — the SQL predicate's parameter. */
    val eventTypeName: String get() = eventType.name

    companion object {
        /** Resolve a stored `outcome` column value; `null` for anything the projector never writes. */
        fun fromEventTypeName(name: String): OfferOutcome? = entries.find { it.eventTypeName == name }
    }
}

/**
 * The offers-list filter (brief §5 — `All / Accepted / Declined / Timed out`). [outcome] is `null`
 * for [ALL], which the read turns into "no outcome predicate at all", so the four chips share ONE
 * query rather than a switch over four (Principle 5).
 */
enum class OfferFilter(val outcome: OfferOutcome?) {
    ALL(null),
    ACCEPTED(OfferOutcome.ACCEPTED),
    DECLINED(OfferOutcome.DECLINED),
    TIMED_OUT(OfferOutcome.TIMED_OUT),
}

/**
 * One row of the Offers tab's recent-offers list (#975 / brief §7.4) — a **frozen decision record**,
 * not a live offer: the pay/distance the card showed, the verdict's frozen estimate and score, and
 * how the driver closed it.
 *
 * **Every economic field is a decision-time figure, never realized net** (§9). [payAmount] and
 * [distanceMiles] are what the offer *quoted*; [estDollarsPerHour] and [score] are what the
 * evaluator projected at that instant. A #936 no-verdict offer carries nulls rather than a
 * fabricated zero, and the UI renders those as the em-dash placeholder — never as a measurement.
 *
 * Privacy: [storeName] is a merchant, driver-owned display data. `offer_records` holds no customer
 * fields at all, so this surface cannot leak one (Principle 6).
 */
data class OfferListing(
    /** The source closing event's `sequenceId` — provenance, and the list's stable key. */
    val eventSequenceId: Long,
    /** Registry-resolved from the stored wire string, never compared as a literal (Principle 8). */
    val platform: Platform,
    /** The offer's parsed merchant (the `displayStores` SSOT's pick at parse time); null if unparsed. */
    val storeName: String?,
    /** When the driver closed the offer (accept / decline / timeout) — the list's sort key. */
    val decidedAt: Long,
    /** The offer's quoted pay; null when the card's pay never parsed. */
    val payAmount: Double?,
    /** The offer's quoted distance; null when it never parsed (the #936 fail-closed input). */
    val distanceMiles: Double?,
    /** FROZEN decision-time est. $/hr; null on a #936 no-verdict evaluation. */
    val estDollarsPerHour: Double?,
    /** FROZEN decision-time score; null when no evaluation landed. */
    val score: Double?,
    /** How it closed; null only if the row carries an outcome this build doesn't know. */
    val outcome: OfferOutcome?,
)
