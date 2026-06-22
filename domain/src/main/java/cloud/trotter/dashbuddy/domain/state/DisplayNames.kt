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
 * person's name (#503 / the premature "Delivery for Customer" card). The dropoff display
 * derives a store-flavored label via [customerLabel] (#568) rather than ever showing the hash.
 */
const val CUSTOMER_FALLBACK = "the customer"

/** The ONE derivation of a short customer display name from the privacy hash. */
fun customerDisplayName(customerHash: String?): String =
    customerHash?.take(6) ?: CUSTOMER_FALLBACK

/**
 * Store-flavored recipient label (#568) — what the dasher actually sees on a dropoff
 * card/bubble. Friendlier than the raw 6-char privacy-hash prefix, and it disambiguates a
 * multi-store stack's drops ("Maple Street's customer" vs "H-E-B's customer"). The customer
 * hash is an identity/dedup key, never a user-facing name, so display derives from the
 * (pickup-resolved) store; it falls back to [CUSTOMER_FALLBACK] when the store hasn't
 * resolved (never the hash). On a same-store stack two drops read alike — an accepted
 * cosmetic trade (the hash prefix was meaningless to the dasher anyway).
 */
fun customerLabel(storeName: String?): String =
    storeName?.takeIf { it.isNotBlank() && it != UNKNOWN_STORE }
        ?.let { "${it}'s customer" }
        ?: CUSTOMER_FALLBACK
