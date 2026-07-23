package cloud.trotter.dashbuddy.domain.model.offer

import kotlinx.serialization.Serializable

import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder

@Serializable

data class ParsedOffer(
    /** A hash generated from core offer details
     *  (store, pay, distance, type, items)
     *  for quick comparison or grouping. */
    val offerHash: String,

    /**
     * Presentation identity — a hash of the STABLE subset of the offer
     * (store names + order count + order types), excluding the ticking economics
     * (pay / distance / time-to-complete) that ALREADY feed [offerHash] (#830).
     *
     * On a platform whose offer card live-re-quotes (Uber re-renders pay/miles/minutes
     * every few seconds), the full-content [offerHash] churns on every re-render while
     * this key stays byte-identical for the SAME physical presentation. The state
     * machine uses it to treat a churned re-render as an *enrich-as-variant* of the
     * offer already on screen rather than a brand-new offer (no replace-storm, no
     * OFFER_TIMEOUT("replaced"), no re-speak, no discarded click latches).
     *
     * Nullable + fail-closed (#362): a null key (sha256 failure, or a rule that
     * emitted no offer fields) degrades to today's replace-on-any-hash-change
     * behavior — never a false MERGE. Platform-agnostic: derived purely from parsed
     * data, no [cloud.trotter.dashbuddy.domain.state.Platform] branch; a stable card
     * (DoorDash) simply enriches same-store re-quotes and replaces on a store change,
     * exactly as before for the fielded corpus.
     */
    val presentationKey: String? = null,

    // -- Order Details --
    /** For "Shop for items" orders, the number of items. Set to 1 for pickup order types. */
    val itemCount: Int = 1,

    /**
     * True when [itemCount] is a **units** count, not an item count (#823 Phase 1) — i.e. the
     * contributing shop order(s) rendered a units-only quantity (`(64 units)`) with no items figure.
     * The [OfferEvaluator] converts a units-denominated count into an items-equivalent (units ×
     * learned per-platform ratio) for the #556 shop-time estimate; the offer card/TTS and the
     * offer-model still surface [itemCount] verbatim (the platform-shown number). False for an
     * items-denominated / estimated / non-shop offer, so those price exactly as before. Aggregated
     * from the per-order [cloud.trotter.dashbuddy.domain.model.order.CountUnit] by
     * `ParsedFieldsFactory`; `@Serializable` default keeps old snapshots decoding unchanged.
     */
    val itemCountIsUnits: Boolean = false,

    /** The numeric value of the guaranteed pay for the offer. */
    val payAmount: Double? = null,

    /**
     *  The full, raw text of the pay information. E.g.,
     *  "$7.75 Guaranteed (incl. tips)"
     *  "$8.50+ Total will be higher".
     */
    val payTextRaw: String? = null,

    /** The numeric distance in miles for the offer. */
    val distanceMiles: Double? = null,

    /** The full, raw text of the distance. E.g., "2.8 mi". */
    val distanceTextRaw: String? = null,

    /**
     * The "Deliver by" time as displayed on the offer, as a string.
     *  E.g., "Deliver by 8:52 PM".
     */
    val dueByTimeText: String? = null,

    val dueByTimeMillis: Long? = null,

    /** The time in minutes to complete this offer. */
    val timeToCompleteMinutes: Long? = null,

    /** Offer-level badges found in the offer. */
    val badges: Set<OfferBadge> = emptySet(),

    /** The initial countdown timer value displayed on the offer screen (e.g., 36 seconds). */
    val initialCountdownSeconds: Int? = null,

    /** List of the orders within this offer. */
    val orders: List<ParsedOrder> = emptyList(),

    /** Store the full extracted text array (joined or as JSON) for this offer screen for later review or parsing. */
    val rawExtractedTexts: String? = null,
)