package cloud.trotter.dashbuddy.services.accessibility.screen

import cloud.trotter.dashbuddy.data.dash.DashType
import cloud.trotter.dashbuddy.data.event.status.DropoffStatus
import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.data.offer.ParsedOffer
import cloud.trotter.dashbuddy.data.pay.ParsedPay

/**
 * A sealed class representing the result of a screen recognition.
 * It contains not only the screen's identity (the 'Screen' enum) but also
 * any relevant, pre-parsed data associated with that screen.
 */
sealed class ScreenInfo {
    abstract val screen: Screen

    /** A generic type for all screens that don't require pre-parsing. */
    data class Simple(override val screen: Screen) : ScreenInfo()

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

    /** Contains the parsed pay details from a Delivery Completed screen. */
    data class DeliveryCompleted(
        override val screen: Screen,
        val parsedPay: ParsedPay
    ) : ScreenInfo()

    /** Contains the extracted zone and earning type from the Idle Map screen. */
    data class IdleMap(
        override val screen: Screen,
        val zoneName: String?,
        val dashType: DashType?,
    ) : ScreenInfo()
}