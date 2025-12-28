package cloud.trotter.dashbuddy.services.accessibility.screen

import cloud.trotter.dashbuddy.data.dash.DashType
import cloud.trotter.dashbuddy.data.event.status.DropoffStatus
import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.data.offer.ParsedOffer
import cloud.trotter.dashbuddy.data.pay.ParsedPay
import cloud.trotter.dashbuddy.services.accessibility.UiNode

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
        val currentDashPay: Double?,      // e.g. 7.50 (if visible)
        val waitTimeEstimate: String?,    // e.g. "1-4 min"
        val isHeadingBackToZone: Boolean  // true if "Heading back to zone" text is present
    ) : ScreenInfo()

    /** Contains the parsed offer details from an Offer Popup screen. */
    data class Offer(override val screen: Screen, val parsedOffer: ParsedOffer) : ScreenInfo()

    /** Contains the OrderStatus either from a pickup or a dropoff. */
    data class OrderDetails(
        override val screen: Screen,
        val storeName: String? = null,
        val storeAddress: String? = null,
        val customerNameHash: String? = null,
        val customerAddressHash: String? = null,
    ) : ScreenInfo()

    /** Specific info for Pickup screens. */
    data class PickupDetails(
        override val screen: Screen,
        val storeName: String? = null,
        val storeAddress: String? = null,
        val customerNameHash: String? = null,
        val status: PickupStatus = PickupStatus.UNKNOWN
    ) : ScreenInfo()

    /** Specific info for Dropoff screens. */
    data class DropoffDetails(
        override val screen: Screen,
        val customerNameHash: String? = null,
        val addressHash: String? = null,
        val status: DropoffStatus = DropoffStatus.UNKNOWN
    ) : ScreenInfo()

    data class DeliverySummaryCollapsed(
        override val screen: Screen,
        val expandButton: UiNode
    ) : ScreenInfo()

    /** Contains the parsed pay details from a Delivery Completed screen. */
    data class DeliveryCompleted(
        override val screen: Screen,
        val parsedPay: ParsedPay,
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
        val remainingMillis: Long,
        val rawTimeText: String
    ) : ScreenInfo()
}