package cloud.trotter.dashbuddy.domain.model.accessibility

import cloud.trotter.dashbuddy.domain.model.dash.DashType
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.DropoffStatus
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay

/**
 * Pairs a human-readable time string with its epoch-millis equivalent.
 * Used anywhere a scheduled time appears in ScreenInfo (deadlines, dash end time, etc.).
 *
 * @param text The raw string as shown on screen, e.g. "Pick up by 17:39" or "Dash ends at 15:00".
 * @param time Epoch millis derived from [text]; null if parsing failed or time is relative.
 */
data class ParsedTime(val text: String, val time: Long?)

/**
 * Pairs a human-readable duration string with its millis equivalent.
 * Used for countdowns where the screen shows remaining time (e.g. "34:15").
 *
 * @param text The raw string as shown on screen, e.g. "34:15".
 * @param millis Duration in milliseconds derived from [text].
 */
data class ParsedDuration(val text: String, val millis: Long)

/**
 * A sealed class representing the result of a screen recognition.
 * It contains not only the screen's identity (the 'Screen' enum) but also
 * any relevant, pre-parsed data associated with that screen.
 */
sealed class ScreenInfo {
    abstract val screen: Screen

    /** A generic type for all screens that don't require pre-parsing. */
    data class Simple(override val screen: Screen) : ScreenInfo()

    /** Contains details from the Waiting for Offer screen. */
    data class WaitingForOffer(
        override val screen: Screen,
        val dashPay: Double?,             // e.g. 7.50 (if visible)
        val waitTimeEstimate: String?,    // e.g. "1-4 min" or "Spot saved until 15:57 (43 mins)"
        val isHeadingBackToZone: Boolean  // true if "Heading back to zone" text is present
    ) : ScreenInfo()

    /** Contains the parsed offer details from an Offer Popup screen. */
    data class Offer(override val screen: Screen, val parsedOffer: ParsedOffer) : ScreenInfo()

    /** Specific info for Pickup screens. */
    data class PickupDetails(
        override val screen: Screen,
        val storeName: String? = null,
        val storeAddress: String? = null,
        val customerNameHash: String? = null,
        val deadline: ParsedTime? = null,   // e.g. text="Pick up by 17:39", time=<epoch millis>
        val itemCount: Int? = null,         // e.g. 4
        val redCardTotal: Double? = null,   // e.g. 23.95 (present when Red Card payment required)
        val status: PickupStatus = PickupStatus.UNKNOWN
    ) : ScreenInfo()

    /** Specific info for Dropoff screens. */
    data class DropoffDetails(
        override val screen: Screen,
        val customerNameHash: String? = null,
        val customerAddressHash: String? = null,
        val deadline: ParsedTime? = null,   // e.g. text="Deliver by 8:16 PM", time=<epoch millis>
        val status: DropoffStatus = DropoffStatus.UNKNOWN
    ) : ScreenInfo()

    /** Contains the parsed pay details from the Delivery Summary screen (collapsed or expanded). */
    data class DeliverySummary(
        override val screen: Screen,

        // State Flags
        val isExpanded: Boolean,

        // The Critical Data (Best effort extraction)
        val totalPay: Double, // Extracted from header (Collapsed) or Breakdown (Expanded)
        val parsedPay: ParsedPay? = null, // Only available if Expanded

        // Interactive Elements
        val expandButton: UiNode? = null, // Only needed if !isExpanded

        // "Data Left on the Table" - Now captured!
        val sessionEarnings: Double? = null, // The running total for the dash
        val offersAccepted: Int? = null,     // e.g. 4
        val offersTotal: Int? = null         // e.g. 18
    ) : ScreenInfo()

    /** Contains the extracted zone and earning type from the Idle Map screen. */
    data class IdleMap(
        override val screen: Screen,
        val zoneName: String?,
        val dashType: DashType?,
    ) : ScreenInfo()

    data class DashSummary(
        override val screen: Screen,
        val totalEarnings: Double?,     // 28.00
        val weeklyEarnings: Double?,    // 495.62
        val offersAccepted: Int,        // 3
        val offersTotal: Int,           // 10
        val onlineDurationMillis: Long, // 4560000
        val estimatedStartTime: Long    // context.timestamp - duration
    ) : ScreenInfo()

    data class DashPaused(
        override val screen: Screen,
        val remaining: ParsedDuration   // e.g. text="34:15", millis=2055000
    ) : ScreenInfo()

    /**
     * A single entry in the active-dash timeline task chain.
     * @param taskType The action prefix as shown on screen, e.g. "Pickup for" or "Deliver to".
     * @param nameHash sha256 of the customer/recipient name.
     * @param deadline The deadline as shown on screen plus epoch millis where parseable.
     *                 Relative strings like "53 min to complete" will have time=null.
     * @param storeHint Store abbreviation appended after " • " in pickup deadlines, e.g. "H-E-B".
     * @param isCurrent True when this task has the "Current task" marker.
     */
    data class TimelineTask(
        val taskType: String,
        val nameHash: String?,
        val deadline: ParsedTime?,
        val storeHint: String?,
        val isCurrent: Boolean = false,
    )

    /** Extracted data from the Timeline / dash-controls overlay. */
    data class Timeline(
        override val screen: Screen,
        val dashEarnings: Double? = null,            // "This dash" amount
        val offerEarnings: Double? = null,           // "This offer" amount; null between tasks
        val endsAt: ParsedTime? = null,              // e.g. text="Dash ends at 15:00", time=<epoch millis>
        val tasks: List<TimelineTask> = emptyList(), // empty when no active task chain
    ) : ScreenInfo()

    /** Extracted performance metrics from the Ratings screen. */
    data class Ratings(
        override val screen: Screen,
        val acceptanceRate: Double? = null,
        val completionRate: Double? = null,
        val onTimeRate: Double? = null,
        val customerRating: Double? = null,
        val deliveriesLast30Days: Int? = null,
        val lifetimeDeliveries: Int? = null,
        val originalItemsFoundRate: Double? = null,
        val totalItemsFoundRate: Double? = null,
        val substitutionIssuesRate: Double? = null,
        val itemsWithQualityIssuesRate: Double? = null,
        val itemsWrongOrMissingRate: Double? = null,
        val lifetimeShoppingOrders: Int? = null,
    ) : ScreenInfo()

    data class Sensitive(override val screen: Screen) : ScreenInfo()
}
