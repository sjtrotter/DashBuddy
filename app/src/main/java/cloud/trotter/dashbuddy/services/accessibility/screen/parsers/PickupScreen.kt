package cloud.trotter.dashbuddy.services.accessibility.screen.parsers

import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.util.UtilityFunctions

object PickupScreen {
    private val tag = this::class.simpleName ?: "PickupScreenParser"

    /**
     * The main public entry point for parsing any screen in the pickup phase.
     * It acts as a facade, delegating to specific private functions based on the screen type.
     *
     * @param texts The list of texts extracted from the screen.
     * @param screen The specific Screen enum that was identified.
     * @return A populated ScreenInfo.PickupDetails object.
     */
    fun parse(texts: List<String>, screen: Screen): ScreenInfo.OrderDetails {
        Log.v(tag, "Parsing screen type: $screen")
        val orderDetails: ScreenInfo.OrderDetails = when (screen) {

            Screen.NAVIGATION_VIEW_TO_PICK_UP -> {
                val storeName = UtilityFunctions.getRelativeText(texts, "Heading to", 1)
                Log.v(tag, "  - Found store name: '$storeName'")
                val storeAddress =
                    UtilityFunctions.parseAddressBetweenAnchors(
                        texts,
                        storeName,
                        "Pick up instructions"
                    )
                Log.v(tag, "  - Found store address: '$storeAddress'")
                ScreenInfo.OrderDetails(screen, storeName = storeName, storeAddress = storeAddress)
            }

            Screen.PICKUP_DETAILS_PRE_ARRIVAL -> {
                val storeName = UtilityFunctions.getRelativeText(texts, "Pickup from", 1)
                Log.v(tag, "  - Found store name: '$storeName'")
                val storeAddress =
                    UtilityFunctions.parseAddressBetweenAnchors(texts, storeName, "Directions")
                Log.v(tag, "  - Found store address: '$storeAddress'")
                ScreenInfo.OrderDetails(screen, storeName = storeName, storeAddress = storeAddress)
            }

            Screen.PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI -> {
                val anchor = texts.find { it.matches(Regex("^\\d+ items$")) }
                Log.v(tag, "  - Found items anchor: '$anchor'")
                val storeName = UtilityFunctions.getRelativeText(texts, anchor, -1)
                Log.v(tag, "  - Found store name: '$storeName'")
                ScreenInfo.OrderDetails(screen, storeName = storeName)
            }

            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE -> {
                val customerName = UtilityFunctions.getRelativeText(texts, "Order for", 1)
                Log.v(tag, "  - Found customer name: '$customerName'")

                var storeAnchor: String? = null
                for (anchor in listOf(
                    "Waiting for your order?",
                    "Verify order",
                    "Continue with pickup",
                    "Confirm pickup"
                )) {
                    if (texts.contains(anchor)) {
                        storeAnchor = anchor
                        Log.v(tag, "  - Found store anchor: '$storeAnchor'")
                        break
                    }
                }

                val storeName = UtilityFunctions.getRelativeText(texts, storeAnchor, -1)
                Log.v(tag, "  - Found store name: '$storeName'")
                val customerNameHash = customerName?.let {
                    val hash = UtilityFunctions.generateSha256(it)
                    Log.v(tag, "  - Generated customer hash for '$customerName'")
                    hash
                }
                ScreenInfo.OrderDetails(
                    screen,
                    storeName = storeName,
                    customerNameHash = customerNameHash
                )
            }

            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_MULTI -> {
                val anchor = texts.find { it.matches(Regex("^\\d+ items$")) }
                Log.v(tag, "  - Found items anchor: '$anchor'")
                val storeName = UtilityFunctions.getRelativeText(texts, anchor, -1)
                Log.v(tag, "  - Found store name: '$storeName'")
                ScreenInfo.OrderDetails(screen, storeName = storeName)
            }

            else -> {
                Log.w(
                    tag,
                    "  - No specific parsing strategy for screen type $screen. Returning empty details."
                )
                ScreenInfo.OrderDetails(screen)
            }
        }
        Log.v(tag, "Parse complete. Result: $orderDetails")
        return orderDetails
    }
}