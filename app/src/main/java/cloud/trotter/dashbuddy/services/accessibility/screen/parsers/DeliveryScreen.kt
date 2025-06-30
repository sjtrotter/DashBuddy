package cloud.trotter.dashbuddy.services.accessibility.screen.parsers

import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.util.UtilityFunctions

object DeliveryScreen {
    private val tag = this::class.simpleName ?: "DeliveryScreenParser"

    fun parse(texts: List<String>, screen: Screen): ScreenInfo.OrderDetails {
        Log.v(tag, "Parsing screen type: $screen")
        return when (screen) {
            Screen.DROPOFF_DETAILS_PRE_ARRIVAL -> {
                val customerName = UtilityFunctions.getRelativeText(texts, "Deliver to", 1)
                val customerAddress =
                    UtilityFunctions.parseAddressBetweenAnchors(texts, customerName, "Directions")
                val customerNameHash = customerName?.let { UtilityFunctions.generateSha256(it) }
                val customerAddressHash =
                    customerAddress?.let { UtilityFunctions.generateSha256(it) }

                Log.v(tag, "  - Found customer name: '$customerName'")
                Log.v(tag, "  - Found customer address: '$customerAddress'")

                // We can often find the store name on this screen too
                val storeName = UtilityFunctions.getRelativeText(texts, "Settings", -1)

                ScreenInfo.OrderDetails(
                    screen,
                    storeName = storeName,
                    customerNameHash = customerNameHash,
                    customerAddressHash = customerAddressHash
                )
            }

            // Add more cases for other delivery screens here later

            else -> {
                Log.w(tag, "  - No specific parsing for screen $screen. Returning empty details.")
                ScreenInfo.OrderDetails(screen)
            }
        }
    }
}