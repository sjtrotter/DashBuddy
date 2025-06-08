package cloud.trotter.dashbuddy.data.offer

import cloud.trotter.dashbuddy.data.order.OrderParser
import cloud.trotter.dashbuddy.data.order.OrderType
import cloud.trotter.dashbuddy.data.order.ParsedOrder
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.util.UtilityFunctions.generateSha256
import cloud.trotter.dashbuddy.util.UtilityFunctions.parseTimeTextToMillis
import kotlin.math.max

object OfferParser {

    private const val TAG = "OfferParser" // Logging tag for this specific parser

    // regexes.
    private val COUNTDOWN_TIMER_REGEX = Regex("^\\d{1,2}$")
    private val PAY_AMOUNT_REGEX = Regex("^\\+?\\s*\\$(\\d+\\.\\d{2}).*$")
    private val DISTANCE_REGEX =
        Regex("""^(?:Additional\s)?(\d+(?:\.\d{1,2})?)\s*(mi|ft)$""", RegexOption.IGNORE_CASE)
    private val DUE_BY_TIME_REGEX =
        Regex("^Deliver by (\\d{1,2}:\\d{2} (AM|PM)).*$") // As per your code
    private const val FEET_IN_A_MILE = 5280.0

    fun parseOffer(screenTexts: List<String>): ParsedOffer? { // Return nullable
        Log.d(TAG, "--- Starting Offer Parsing ---")
        Log.v(TAG, "Input screenTexts (count: ${screenTexts.size}): $screenTexts")

        if (screenTexts.isEmpty()) {
            Log.w(TAG, "!!! Offer screen texts are empty! Returning null.")
            return null
        }

        // able to set right away
        Log.d(TAG, "Extracting initial details...")
        val badges: Set<OfferBadge> = OfferBadge.findAllBadgesInScreen(screenTexts)
        Log.d(TAG, "Offer-level badges found: $badges")

        val payTextRaw = screenTexts.find { PAY_AMOUNT_REGEX.matches(it.trim()) } // Added trim()
        Log.d(TAG, "Pay text raw: $payTextRaw")

        val distanceTextRaw = screenTexts.find { DISTANCE_REGEX.matches(it.trim()) } // Added trim()
        Log.d(TAG, "Distance text raw: $distanceTextRaw")

        val dueByTimeTextLine =
            screenTexts.find { DUE_BY_TIME_REGEX.matches(it.trim()) } // Added trim(), full line
        Log.d(TAG, "Due by time line raw: $dueByTimeTextLine")

        val textsToFilterForHash =
            screenTexts.map { it.trim() } // Trim before filtering for hash consistency
        val rawExtractedTextsForHash: String =
            textsToFilterForHash.filterNot { COUNTDOWN_TIMER_REGEX.matches(it) }
                .joinToString(separator = "|") // Corrected joinToString
        Log.v(
            TAG,
            "Raw texts for HASH (countdown filtered, joined by |): $rawExtractedTextsForHash"
        )
        val offerHash = generateSha256(rawExtractedTextsForHash)
        Log.d(TAG, "Offer Hash: $offerHash")

        val initialCountdownSeconds =
            screenTexts.find { COUNTDOWN_TIMER_REGEX.matches(it.trim()) }
                ?.toIntOrNull() // Added trim() and toIntOrNull
        Log.d(TAG, "Initial countdown seconds: $initialCountdownSeconds")

        // legwork before iteration
        Log.d(TAG, "Extracting numeric values and heuristics...")
        val payAmount =
            payTextRaw?.let {
                PAY_AMOUNT_REGEX.find(it)?.groupValues?.get(1)?.toDoubleOrNull()
            } // Added toDoubleOrNull
        Log.d(TAG, "Pay amount: $payAmount")

        val distanceMiles = distanceTextRaw?.let {
            DISTANCE_REGEX.find(it.trim())?.let { matchResult ->
                val numericValueString = matchResult.groupValues.getOrNull(1)
                val unitString = matchResult.groupValues.getOrNull(2)

                val numericValue = numericValueString?.toDoubleOrNull()
                if (numericValue == null || unitString == null) {
                    null // Failed to parse number or unit
                } else {
                    var convertedMiles = when (unitString.lowercase()) {
                        "ft" -> {
                            Log.d(
                                TAG,
                                "Found distance in feet: $numericValue ft. Converting to miles."
                            )
                            numericValue / FEET_IN_A_MILE
                        }

                        "mi" -> {
                            Log.d(TAG, "Found distance in miles: $numericValue mi.")
                            numericValue
                        }

                        else -> {
                            Log.w(
                                TAG,
                                "Found unknown distance unit: '$unitString'. Treating as miles."
                            )
                            numericValue // Default to treating as miles if unit is unknown
                        }
                    }

                    // If the final calculated distance is 0.0, change it to a small default value.
                    if (convertedMiles == 0.0) {
                        Log.w(TAG, "Parsed distance is 0.0 miles, changing to 0.1 for scoring.")
                        convertedMiles = 0.1
                    }

                    convertedMiles
                }
            }
        }

        val dueByTimeActual =
            dueByTimeTextLine?.let { DUE_BY_TIME_REGEX.find(it.trim())?.groupValues?.get(1) } // Extracted time part
        Log.d(TAG, "Due by time actual string: $dueByTimeActual")
        val dueByTimeMillis = dueByTimeActual?.let { parseTimeTextToMillis(it) }
        Log.d(TAG, "Due by time millis: $dueByTimeMillis")

        val orderTypeLineCount = OrderType.orderTypeCount(screenTexts)
        Log.d(TAG, "OrderType lines count: $orderTypeLineCount")
        val customerDropoffLineCount =
            screenTexts.count { it.equals("Customer dropoff", ignoreCase = true) }
        Log.d(TAG, "Customer dropoff lines count: $customerDropoffLineCount")

        // This is your heuristic for the number of orders.
        val heuristicNumberOfOrders =
            max(orderTypeLineCount, customerDropoffLineCount).takeIf { it > 0 } ?: 1
        Log.d(TAG, "Heuristic number of orders: $heuristicNumberOfOrders")

        // Determine ordersRegionTexts
        val firstOrderTypeGlobalIndex =
            screenTexts.indexOfFirst { OrderType.fromTypeName(it.trim()) != null }
        Log.d(TAG, "First OrderType index: $firstOrderTypeGlobalIndex")

        // Using case-insensitive search for "Customer dropoff" as it's a literal string
        val firstCustomerDropoffGlobalIndex =
            screenTexts.indexOfFirst { it.equals("Customer dropoff", ignoreCase = true) }
        Log.d(TAG, "First Customer Dropoff index: $firstCustomerDropoffGlobalIndex")


        val ordersRegionTexts = if (firstOrderTypeGlobalIndex != -1 &&
            firstCustomerDropoffGlobalIndex != -1 &&
            firstOrderTypeGlobalIndex < firstCustomerDropoffGlobalIndex
        ) {
            Log.d(
                TAG,
                "Defining ordersRegionTexts from index $firstOrderTypeGlobalIndex to $firstCustomerDropoffGlobalIndex"
            )
            screenTexts.subList(firstOrderTypeGlobalIndex, firstCustomerDropoffGlobalIndex)
        } else if (firstOrderTypeGlobalIndex != -1 && firstCustomerDropoffGlobalIndex == -1) {
            Log.w(
                TAG,
                "No 'Customer dropoff' found after first OrderType. Defining ordersRegionTexts from first OrderType to end of texts, or before 'Accept'."
            )
            val acceptButtonIndex =
                screenTexts.indexOfFirst { it.equals("Accept", ignoreCase = true) }
            val endIndex =
                if (acceptButtonIndex != -1 && acceptButtonIndex > firstOrderTypeGlobalIndex) {
                    acceptButtonIndex
                } else {
                    screenTexts.size
                }
            Log.d(
                TAG,
                "Fallback ordersRegionTexts from index $firstOrderTypeGlobalIndex to $endIndex"
            )
            screenTexts.subList(firstOrderTypeGlobalIndex, endIndex)
        } else {
            Log.w(
                TAG,
                "Could not define a valid ordersRegionTexts. firstOrderTypeIndex: $firstOrderTypeGlobalIndex, firstCustomerDropoffIndex: $firstCustomerDropoffGlobalIndex. Proceeding with empty orders region."
            )
            emptyList()
        }
        Log.v(TAG, "OrdersRegionTexts (count: ${ordersRegionTexts.size}): $ordersRegionTexts")

        Log.d(
            TAG,
            "Calling OrderParser.parseOrders with heuristicNumberOfOrders: $heuristicNumberOfOrders"
        )
        val orders: List<ParsedOrder> =
            OrderParser.parseOrders(ordersRegionTexts, heuristicNumberOfOrders)

        // MODIFIED: Stricter check for returning null based on test expectation
        if (payAmount == null && distanceMiles == null) {
            Log.w(TAG, "!!! Offer missing both PayAmount and DistanceMiles.")
            return null
        }

        if (orders.isEmpty()) { // This check is now after the pay/distance check
            Log.w(
                TAG,
                "!!! No orders were parsed! (And Pay/Distance might be present). Returning null."
            )
            return null
        }
        Log.d(TAG, "Parsed orders count: ${orders.size}")
        Log.v(TAG, "Parsed orders list: $orders")


        // Simplified finalItemCount calculation as orders.isNotEmpty() is guaranteed here
        val finalItemCount = orders.sumOf { it.itemCount }.takeIf { sum -> sum > 0 }
            ?: orders.size // If sum is 0 (e.g. all non-shop with default 1 item), use number of orders.
        // This assumes each ParsedOrder has at least 1 item conceptually if sum is 0.
        Log.d(TAG, "Final calculated itemCount for ParsedOffer: $finalItemCount")

        // Removed the very broad null check that was here, as specific checks are now earlier.

        val parsedOffer = ParsedOffer(
            offerHash = offerHash,
            itemCount = finalItemCount,
            distanceMiles = distanceMiles,
            payAmount = payAmount,
            payTextRaw = payTextRaw,
            distanceTextRaw = distanceTextRaw,
            dueByTimeText = dueByTimeActual,
            dueByTimeMillis = dueByTimeMillis,
            badges = badges,
            initialCountdownSeconds = initialCountdownSeconds,
            orders = orders,
            rawExtractedTexts = rawExtractedTextsForHash
        )

        Log.i(TAG, "Successfully parsed offer: $parsedOffer")
        return parsedOffer
    }
}
