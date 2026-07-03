package cloud.trotter.dashbuddy.domain.model.pay

import kotlinx.serialization.Serializable

/**
 * A data class to hold a single component of pay parsed from the screen.
 * This is a generic holder used by both app pay and tips.
 * For tips, the 'type' field will be the store name.
 */
@Serializable
data class ParsedPayItem(
    val type: String,   // e.g., "Base Pay", "Peak Pay", or for tips, "Sake Cafe"
    val amount: Double
)

/** Bare-digits store identifier — the shape DoorDash uses as a tip's own label for some merchants. */
private val BARE_STORE_NUMBER = Regex("^\\d{2,6}$")

/**
 * Display-only rendering of [ParsedPayItem.type] for human-facing UI (the bubble HUD post-task
 * receipt, the delivery-summary card).
 *
 * For some merchants, DoorDash's own per-tip sub-label (the `pay_line_item_title` node) is a bare
 * store number (`"618"`) with no fuller name anywhere in the frame — that number IS DoorDash's
 * label, not junk data (#607); it's stable and recurs across separate deliveries at the same
 * store. Rendered verbatim it reads as a meaningless number to a driver, so this formats it as
 * `"Store #618"`.
 *
 * [ParsedPayItem.type] itself is left completely untouched everywhere else — in particular,
 * [cloud.trotter.dashbuddy.domain.state.StoreChainProjector] token-matches the raw `.type`
 * against pickup store names (and tears a running key out of it), and a normalized `"Store #618"`
 * would tokenize to `["store", "618"]`, corrupting that match. This property is strictly a
 * display-site helper — use it only where you're building text for a human to read, never where
 * you're comparing, keying, or correlating on the value.
 */
val ParsedPayItem.displayLabel: String
    get() = if (BARE_STORE_NUMBER.matches(type)) "Store #$type" else type