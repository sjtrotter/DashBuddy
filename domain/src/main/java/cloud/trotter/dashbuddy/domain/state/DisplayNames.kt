package cloud.trotter.dashbuddy.domain.state

/**
 * Shared display-name sentinels and derivations (#403). Each existed as
 * scattered matching literals across the state layer and the bubble UI —
 * one owner now, so the fallbacks can't drift.
 */

/** Store-name sentinel for a task whose store hasn't resolved yet. */
const val UNKNOWN_STORE = "Unknown"

/** Customer display fallback when no name hash is available. */
const val CUSTOMER_FALLBACK = "Customer"

/** The ONE derivation of a short customer display name from the privacy hash. */
fun customerDisplayName(customerHash: String?): String =
    customerHash?.take(6) ?: CUSTOMER_FALLBACK
