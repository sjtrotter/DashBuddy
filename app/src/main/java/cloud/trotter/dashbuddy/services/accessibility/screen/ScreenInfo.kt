package cloud.trotter.dashbuddy.services.accessibility.screen

import cloud.trotter.dashbuddy.data.dash.DashType
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

    /** Contains the parsed store details from a Pickup Details screen. */
    data class PickupDetails(
        override val screen: Screen,
        val storeName: String?,
        val storeAddress: String?,
        val customerNameHash: String?
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