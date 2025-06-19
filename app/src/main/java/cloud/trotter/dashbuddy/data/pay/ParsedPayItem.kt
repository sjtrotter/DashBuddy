package cloud.trotter.dashbuddy.data.pay

/**
 * A data class to hold a single component of pay parsed from the screen.
 * This is a generic holder used by both app pay and tips.
 * For tips, the 'type' field will be the store name.
 */
data class ParsedPayItem(
    val type: String,   // e.g., "Base Pay", "Peak Pay", or for tips, "Sake Cafe"
    val amount: Double
)