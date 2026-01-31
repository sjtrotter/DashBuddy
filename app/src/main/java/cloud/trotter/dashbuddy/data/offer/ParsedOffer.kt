package cloud.trotter.dashbuddy.data.offer

import cloud.trotter.dashbuddy.data.order.ParsedOrder

data class ParsedOffer(
    /** A hash generated from core offer details
     *  (store, pay, distance, type, items)
     *  for quick comparison or grouping. */
    val offerHash: String,

    // -- Order Details --
    /** For "Shop for items" orders, the number of items. Set to 1 for pickup order types. */
    val itemCount: Int = 1,

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