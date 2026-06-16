package cloud.trotter.dashbuddy.domain.state

/**
 * Shared display-name sentinels and derivations (#403). Each existed as
 * scattered matching literals across the state layer and the bubble UI —
 * one owner now, so the fallbacks can't drift.
 */

/** Store-name sentinel for a task whose store hasn't resolved yet. */
const val UNKNOWN_STORE = "Unknown"

/**
 * Display label for a recipient whose name hash hasn't resolved yet. Deliberately a
 * lowercase generic phrase, not the proper-noun "Customer" — a dropoff card/message that
 * surfaces this must read as "recipient not yet known", never masquerade as a specific
 * person's name (#503 / the premature "Delivery for Customer" card). A real recipient
 * shows the 6-char privacy-hash prefix instead.
 */
const val CUSTOMER_FALLBACK = "the customer"

/** The ONE derivation of a short customer display name from the privacy hash. */
fun customerDisplayName(customerHash: String?): String =
    customerHash?.take(6) ?: CUSTOMER_FALLBACK
