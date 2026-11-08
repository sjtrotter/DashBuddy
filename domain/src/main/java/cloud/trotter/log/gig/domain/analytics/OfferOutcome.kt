package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.AppEventType

/**
 * How a presented offer closed (#975) — the typed form of the `offer_records.outcome` column, which stores an
 * [AppEventType] name. A data type, not a stringly-typed value (Principle 4), and the **one owner** of the
 * wire↔display mapping: the shift timeline and every SQL predicate resolve through here rather than each spelling
 * `"OFFER_DECLINED"` (Principle 5).
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
