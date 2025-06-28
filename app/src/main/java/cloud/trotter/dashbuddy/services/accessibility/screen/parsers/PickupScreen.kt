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
    fun parse(texts: List<String>, screen: Screen): ScreenInfo.PickupDetails {
        Log.v(tag, "Parsing screen type: $screen")
        val pickupDetails: ScreenInfo.PickupDetails = when (screen) {

            Screen.NAVIGATION_VIEW_TO_PICK_UP -> {
                val storeName = getRelativeText(texts, "Heading to", 1)
                Log.v(tag, "  - Found store name: '$storeName'")
                val storeAddress =
                    parseAddressBetweenAnchors(texts, storeName, "Pick up instructions")
                Log.v(tag, "  - Found store address: '$storeAddress'")
                ScreenInfo.PickupDetails(screen, storeName, storeAddress, null)
            }

            Screen.PICKUP_DETAILS_PRE_ARRIVAL -> {
                val storeName = getRelativeText(texts, "Pickup from", 1)
                Log.v(tag, "  - Found store name: '$storeName'")
                val storeAddress =
                    parseAddressBetweenAnchors(texts, storeName, "Directions")
                Log.v(tag, "  - Found store address: '$storeAddress'")
                ScreenInfo.PickupDetails(screen, storeName, storeAddress, null)
            }

            Screen.PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI -> {
                val anchor = texts.find { it.matches(Regex("^\\d+ items$")) }
                Log.v(tag, "  - Found items anchor: '$anchor'")
                val storeName = getRelativeText(texts, anchor, -1)
                Log.v(tag, "  - Found store name: '$storeName'")
                ScreenInfo.PickupDetails(screen, storeName, null, null)
            }

            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE -> {
                val customerName = getRelativeText(texts, "Order for", 1)
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

                val storeName = getRelativeText(texts, storeAnchor, -1)
                Log.v(tag, "  - Found store name: '$storeName'")
                val customerHash = customerName?.let {
                    val hash = UtilityFunctions.generateSha256(it)
                    Log.v(tag, "  - Generated customer hash for '$customerName'")
                    hash
                }
                ScreenInfo.PickupDetails(screen, storeName, null, customerHash)
            }

            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_MULTI -> {
                val anchor = texts.find { it.matches(Regex("^\\d+ items$")) }
                Log.v(tag, "  - Found items anchor: '$anchor'")
                val storeName = getRelativeText(texts, anchor, -1)
                Log.v(tag, "  - Found store name: '$storeName'")
                ScreenInfo.PickupDetails(screen, storeName, null, null)
            }

            else -> {
                Log.w(
                    tag,
                    "  - No specific parsing strategy for screen type $screen. Returning empty details."
                )
                ScreenInfo.PickupDetails(screen, null, null, null)
            }
        }
        Log.v(tag, "Parse complete. Result: $pickupDetails")
        return pickupDetails
    }

    // --- PRIVATE HELPER FUNCTIONS ---
    /**
     * A more generic address parser that finds the address between two known anchor texts.
     * It intelligently finds the start of the address by looking for a numeric street number.
     */
    private fun parseAddressBetweenAnchors(
        texts: List<String>,
        startAnchor: String?,
        endAnchor: String
    ): String? {
        if (startAnchor == null) {
            Log.w(tag, "Cannot parse address without a start anchor.")
            return null
        }
        val startIndex = texts.lastIndexOf(startAnchor)
        val endIndex = texts.indexOf(endAnchor)

        if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
            Log.w(
                tag,
                "Address parsing failed: could not find anchors '$startAnchor' and '$endAnchor' in order."
            )
            return null
        }

        // The address content is the slice of the list between the two anchors.
        val contentSlice = texts.subList(startIndex + 1, endIndex)

        // Find the start of the address within the slice by looking for the first numeric part.
        val addressStartInSlice = contentSlice.indexOfFirst { it.matches(Regex("^\\d+.*")) }

        if (addressStartInSlice == -1) {
            Log.w(tag, "Could not find a numeric start to the address within the slice.")
            return null
        }

        // The actual address is from that numeric start to the end of the slice.
        val addressParts = contentSlice.subList(addressStartInSlice, contentSlice.size)

        return addressParts
            .joinToString(" ")
            .replace(" , ", ", ")
            .replace("Apt/Suite: ", "")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun getRelativeText(texts: List<String>, anchor: String?, offset: Int): String? {
        if (anchor == null) {
            // This case is expected when an anchor isn't found, so no log is needed here.
            return null
        }
        val anchorIndex = texts.indexOf(anchor)
        if (anchorIndex == -1) {
            // This is also expected if the anchor from a find operation isn't in the list.
            return null
        }

        val targetIndex = anchorIndex + offset
        return if (targetIndex >= 0 && targetIndex < texts.size) {
            texts[targetIndex]
        } else {
            Log.w(
                tag,
                "Relative text lookup failed: offset $offset from anchor '$anchor' (index $anchorIndex) is out of bounds."
            )
            null
        }
    }
}