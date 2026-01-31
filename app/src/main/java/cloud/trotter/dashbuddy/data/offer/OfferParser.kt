package cloud.trotter.dashbuddy.data.offer

import cloud.trotter.dashbuddy.data.order.OrderParser
import cloud.trotter.dashbuddy.data.order.OrderType
import cloud.trotter.dashbuddy.data.order.ParsedOrder
//import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.util.UtilityFunctions.generateSha256
import cloud.trotter.dashbuddy.util.UtilityFunctions.parseTimeTextToMillis
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class OfferParser @Inject constructor(
    private val orderParser: OrderParser // <--- Dependency Injection!
) {
    // regexes.
    private val countdownTimerRegex = Regex("^\\d{1,2}$")
    private val payAmountRegex = Regex("^\\+?\\s*\\$(\\d+\\.\\d{2}).*$")
    private val distanceRegex =
        Regex("""^(?:Additional\s)?(\d+(?:\.\d{1,2})?)\s*(mi|ft)$""", RegexOption.IGNORE_CASE)
    private val dueByTimeRegex =
        Regex("^Deliver by (\\d{1,2}:\\d{2} (AM|PM)).*$") // As per your code
    private val feetInMile = 5280.0

    fun parseOffer(screenTexts: List<String>): ParsedOffer? { // Return nullable
        Timber.d("--- Starting Offer Parsing ---")
        Timber.v("Input screenTexts (count: ${screenTexts.size}): $screenTexts")

        if (screenTexts.isEmpty()) {
            Timber.w("!!! Offer screen texts are empty! Returning null.")
            return null
        }

        // able to set right away
        Timber.d("Extracting initial details...")
        val badges: Set<OfferBadge> = OfferBadge.findAllBadgesInScreen(screenTexts)
        Timber.d("Offer-level badges found: $badges")

        val payTextRaw = screenTexts.find { payAmountRegex.matches(it.trim()) } // Added trim()
        Timber.d("Pay text raw: $payTextRaw")

        val distanceTextRaw = screenTexts.find { distanceRegex.matches(it.trim()) } // Added trim()
        Timber.d("Distance text raw: $distanceTextRaw")

        val dueByTimeTextLine =
            screenTexts.find { dueByTimeRegex.matches(it.trim()) } // Added trim(), full line
        Timber.d("Due by time line raw: $dueByTimeTextLine")

        val textsToFilterForHash =
            screenTexts.map { it.trim() } // Trim before filtering for hash consistency
        val rawExtractedTextsForHash: String =
            textsToFilterForHash.filterNot { countdownTimerRegex.matches(it) }
                .joinToString(separator = "|") // Corrected joinToString
        Timber.v(
            "Raw texts for HASH (countdown filtered, joined by |): $rawExtractedTextsForHash"
        )
        val offerHash = generateSha256(rawExtractedTextsForHash)
        Timber.d("Offer Hash: $offerHash")

        val initialCountdownSeconds =
            screenTexts.find { countdownTimerRegex.matches(it.trim()) }
                ?.toIntOrNull() // Added trim() and toIntOrNull
        Timber.d("Initial countdown seconds: $initialCountdownSeconds")

        // legwork before iteration
        Timber.d("Extracting numeric values and heuristics...")
        val payAmount =
            payTextRaw?.let {
                payAmountRegex.find(it)?.groupValues?.get(1)?.toDoubleOrNull()
            } // Added toDoubleOrNull
        Timber.d("Pay amount: $payAmount")

        val distanceMiles = distanceTextRaw?.let {
            distanceRegex.find(it.trim())?.let { matchResult ->
                val numericValueString = matchResult.groupValues.getOrNull(1)
                val unitString = matchResult.groupValues.getOrNull(2)

                val numericValue = numericValueString?.toDoubleOrNull()
                if (numericValue == null || unitString == null) {
                    null // Failed to parse number or unit
                } else {
                    var convertedMiles = when (unitString.lowercase()) {
                        "ft" -> {
                            Timber.d(
                                "Found distance in feet: $numericValue ft. Converting to miles."
                            )
                            numericValue / feetInMile
                        }

                        "mi" -> {
                            Timber.d("Found distance in miles: $numericValue mi.")
                            numericValue
                        }

                        else -> {
                            Timber.w(
                                "Found unknown distance unit: '$unitString'. Treating as miles."
                            )
                            numericValue // Default to treating as miles if unit is unknown
                        }
                    }

                    // If the final calculated distance is 0.0, change it to a small default value.
                    if (convertedMiles == 0.0) {
                        Timber.w("Parsed distance is 0.0 miles, changing to 0.1 for scoring.")
                        convertedMiles = 0.1
                    }

                    convertedMiles
                }
            }
        }

        val dueByTimeActual =
            dueByTimeTextLine?.let { dueByTimeRegex.find(it.trim())?.groupValues?.get(1) } // Extracted time part
        Timber.d("Due by time actual string: $dueByTimeActual")
        val dueByTimeMillis = dueByTimeActual?.let { parseTimeTextToMillis(it) }
        Timber.d("Due by time millis: $dueByTimeMillis")

        val orderTypeLineCount = OrderType.orderTypeCount(screenTexts)
        Timber.d("OrderType lines count: $orderTypeLineCount")
        val customerDropoffLineCount =
            screenTexts.count { it.equals("Customer dropoff", ignoreCase = true) }
        Timber.d("Customer dropoff lines count: $customerDropoffLineCount")

        // This is your heuristic for the number of orders.
        val heuristicNumberOfOrders =
            max(orderTypeLineCount, customerDropoffLineCount).takeIf { it > 0 } ?: 1
        Timber.d("Heuristic number of orders: $heuristicNumberOfOrders")

        // Determine ordersRegionTexts
        val firstOrderTypeGlobalIndex =
            screenTexts.indexOfFirst { OrderType.fromTypeName(it.trim()) != null }
        Timber.d("First OrderType index: $firstOrderTypeGlobalIndex")

        // Using case-insensitive search for "Customer dropoff" as it's a literal string
        val firstCustomerDropoffGlobalIndex =
            screenTexts.indexOfFirst { it.equals("Customer dropoff", ignoreCase = true) }
        Timber.d("First Customer Dropoff index: $firstCustomerDropoffGlobalIndex")


        val ordersRegionTexts = if (firstOrderTypeGlobalIndex != -1 &&
            firstCustomerDropoffGlobalIndex != -1 &&
            firstOrderTypeGlobalIndex < firstCustomerDropoffGlobalIndex
        ) {
            Timber.d(
                "Defining ordersRegionTexts from index $firstOrderTypeGlobalIndex to $firstCustomerDropoffGlobalIndex"
            )
            screenTexts.subList(firstOrderTypeGlobalIndex, firstCustomerDropoffGlobalIndex)
        } else if (firstOrderTypeGlobalIndex != -1 && firstCustomerDropoffGlobalIndex == -1) {
            Timber.w(
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
            Timber.d(
                "Fallback ordersRegionTexts from index $firstOrderTypeGlobalIndex to $endIndex"
            )
            screenTexts.subList(firstOrderTypeGlobalIndex, endIndex)
        } else {
            Timber.w(
                "Could not define a valid ordersRegionTexts. firstOrderTypeIndex: $firstOrderTypeGlobalIndex, firstCustomerDropoffIndex: $firstCustomerDropoffGlobalIndex. Proceeding with empty orders region."
            )
            emptyList()
        }
        Timber.v("OrdersRegionTexts (count: ${ordersRegionTexts.size}): $ordersRegionTexts")

        Timber.d(
            "Calling OrderParser.parseOrders with heuristicNumberOfOrders: $heuristicNumberOfOrders"
        )
        val orders: List<ParsedOrder> =
            orderParser.parseOrders(ordersRegionTexts, heuristicNumberOfOrders)

        // MODIFIED: Stricter check for returning null based on test expectation
        if (payAmount == null && distanceMiles == null) {
            Timber.w("!!! Offer missing both PayAmount and DistanceMiles.")
            return null
        }

        if (orders.isEmpty()) { // This check is now after the pay/distance check
            Timber.w(
                "!!! No orders were parsed! (And Pay/Distance might be present). Returning null."
            )
            return null
        }
        Timber.d("Parsed orders count: ${orders.size}")
        Timber.v("Parsed orders list: $orders")


        // Simplified finalItemCount calculation as orders.isNotEmpty() is guaranteed here
        val finalItemCount = orders.sumOf { it.itemCount }.takeIf { sum -> sum > 0 }
            ?: orders.size // If sum is 0 (e.g. all non-shop with default 1 item), use number of orders.
        // This assumes each ParsedOrder has at least 1 item conceptually if sum is 0.
        Timber.d("Final calculated itemCount for ParsedOffer: $finalItemCount")

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

        Timber.i("Successfully parsed offer: $parsedOffer")
        return parsedOffer
    }
}
