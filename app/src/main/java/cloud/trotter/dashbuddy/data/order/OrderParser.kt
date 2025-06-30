package cloud.trotter.dashbuddy.data.order // Assuming it belongs in this package

import cloud.trotter.dashbuddy.log.Logger as Log
import kotlin.math.roundToInt

// Assuming OrderType, ParsedOrder, and OrderBadge (as OrderSpecificBadge) are correctly defined and imported
// For example:
// import cloud.trotter.dashbuddy.data.order.OrderType
// import cloud.trotter.dashbuddy.data.order.ParsedOrder
// import cloud.trotter.dashbuddy.data.order.OrderBadge as OrderSpecificBadge


object OrderParser {

    private const val TAG = "OrderParser"

    // Regexes for parsing details within an order's text block
    private val ITEM_COUNT_SIMPLE_REGEX = Regex("""\((\d+)\s*(?:item|items)\)""") // For (X items)
    private val STACKED_ORDER_ITEM_COUNT_REGEX = Regex(
        """\((\d+)\s*orders?,\s*(\d+)\s*items\)| \((\d+)\s*items,\s*(\d+)\s*orders?\)""",
        RegexOption.IGNORE_CASE
    ) // For (X orders, Y items)
    private val MULTI_ORDER_ONLY_REGEX = Regex("""\((\d+)\s*orders?\)""") // For just (X orders)

    /**
     * Parses a list of strings representing the "orders region" of an offer screen.
     * This region starts with the first OrderType and ends before the first "Customer dropoff".
     *
     * @param ordersRegionTexts The sub-list of screen texts containing all order blocks.
     * @param totalOrders A hint from OfferParser about the expected number of orders.
     * @return A list of ParsedOrder objects.
     */
    fun parseOrders(
        ordersRegionTexts: List<String>,
        totalOrders: Int // Renamed from heuristicTotalOrders for clarity from user's code
    ): List<ParsedOrder> {
        Log.d(
            TAG,
            "parseOrders called. Region texts count: ${ordersRegionTexts.size}, Heuristic totalOrders: $totalOrders"
        )
        Log.v(TAG, "OrdersRegionTexts: $ordersRegionTexts") // Verbose log for full text

        val parsedOrdersResult = mutableListOf<ParsedOrder>()
        if (ordersRegionTexts.isEmpty()) {
            if (totalOrders > 0) {
                Log.w(
                    TAG,
                    "Orders region is empty, but heuristicTotalOrders ($totalOrders) > 0. Returning empty list."
                )
            } else {
                Log.d(TAG, "Orders region is empty. Returning empty list.")
            }
            return parsedOrdersResult
        }

        val orderHeaderIndicesInRegion = ordersRegionTexts.mapIndexedNotNull { index, text ->
            if (OrderType.fromTypeName(text.trim()) != null) index else null
        }
        Log.d(
            TAG,
            "Found ${orderHeaderIndicesInRegion.size} order headers at indices: $orderHeaderIndicesInRegion"
        )

        var currentGlobalOrderIndex = 0

        orderHeaderIndicesInRegion.forEachIndexed { idx, headerIndexInRegion ->
            Log.d(TAG, "Processing order header #$idx at region index: $headerIndexInRegion")
            val orderType = OrderType.fromTypeName(ordersRegionTexts[headerIndexInRegion].trim())!!
            Log.d(TAG, "OrderType: ${orderType.typeName}")

            val storeNameIndexInRegion = headerIndexInRegion + 1
            if (storeNameIndexInRegion >= ordersRegionTexts.size) {
                Log.w(
                    TAG,
                    "Malformed order block: OrderType found at end of region texts, no store name. Skipping."
                )
                return@forEachIndexed
            }

            val storeName = ordersRegionTexts[storeNameIndexInRegion].trim()
            Log.d(TAG, "StoreName: $storeName")

            val nextOrderHeaderInRegion = orderHeaderIndicesInRegion.getOrNull(idx + 1)
            val detailsEndIndexInRegion = nextOrderHeaderInRegion ?: ordersRegionTexts.size
            Log.d(
                TAG,
                "Detail texts for this order will be from region index ${storeNameIndexInRegion + 1} to $detailsEndIndexInRegion"
            )

            val detailTextsForThisOrder =
                if (storeNameIndexInRegion + 1 < detailsEndIndexInRegion) {
                    ordersRegionTexts.subList(storeNameIndexInRegion + 1, detailsEndIndexInRegion)
                } else {
                    emptyList()
                }
            Log.v(TAG, "DetailTextsForThisOrder: $detailTextsForThisOrder")

            var itemsInThisOrderLeg = if (orderType.isShoppingOrder) 0 else 1
            var isItemCountEstimated = false
            var numOrdersFromPattern = 1
            val orderBadgesFound: Set<OrderBadge> =
                OrderBadge.findAllBadgesInOrderBlock(detailTextsForThisOrder)
            Log.d(TAG, "OrderSpecificBadges found for this order: $orderBadgesFound")
            var patternMatchedThisOrder = false

            detailTextsForThisOrder.forEach { detailText ->
                Log.v(TAG, "Scanning detailText: '$detailText'")
                if (!patternMatchedThisOrder) {
                    val stackedMatch = STACKED_ORDER_ITEM_COUNT_REGEX.find(detailText)
                    if (stackedMatch != null) {
                        Log.d(TAG, "STACKED_ORDER_ITEM_COUNT_REGEX matched: $detailText")
                        val ordersStr =
                            stackedMatch.groupValues[1].ifEmpty { stackedMatch.groupValues[4] }
                        val itemsStr =
                            stackedMatch.groupValues[2].ifEmpty { stackedMatch.groupValues[3] }
                        numOrdersFromPattern = ordersStr.toIntOrNull() ?: 1
                        val totalItems = itemsStr.toIntOrNull() ?: 0
                        itemsInThisOrderLeg =
                            if (numOrdersFromPattern > 0)
                                (totalItems.toDouble() / numOrdersFromPattern).roundToInt() else 0
                        isItemCountEstimated = true
                        patternMatchedThisOrder = true
                        Log.d(
                            TAG,
                            "Parsed from stacked: numOrders=$numOrdersFromPattern, itemsPerOrder=$itemsInThisOrderLeg (estimated)"
                        )
                    } else {
                        MULTI_ORDER_ONLY_REGEX.find(detailText)?.groupValues?.get(1)?.toIntOrNull()
                            ?.let {
                                Log.d(TAG, "MULTI_ORDER_ONLY_REGEX matched: $detailText")
                                numOrdersFromPattern = it
                                itemsInThisOrderLeg = 1
                                isItemCountEstimated = false
                                patternMatchedThisOrder = true
                                Log.d(
                                    TAG,
                                    "Parsed from multi-order: numOrders=$numOrdersFromPattern, itemsPerOrder=$itemsInThisOrderLeg"
                                )
                            }
                        if (!patternMatchedThisOrder) {
                            ITEM_COUNT_SIMPLE_REGEX.find(detailText)?.groupValues?.get(1)
                                ?.toIntOrNull()?.let {
                                    Log.d(TAG, "ITEM_COUNT_SIMPLE_REGEX matched: $detailText")
                                    itemsInThisOrderLeg = it
                                    patternMatchedThisOrder = true
                                    Log.d(
                                        TAG,
                                        "Parsed from simple item count: items=$itemsInThisOrderLeg"
                                    )
                                }
                        }
                    }
                }
            }

            if (orderType.isShoppingOrder && !patternMatchedThisOrder && itemsInThisOrderLeg == 0) {
                itemsInThisOrderLeg = 0
                Log.d(TAG, "Shop order, no item pattern matched, itemsInThisOrderLeg remains 0.")
            }
            Log.d(
                TAG,
                "Final for this leg: items=$itemsInThisOrderLeg, numSubOrders=$numOrdersFromPattern, estimated=$isItemCountEstimated"
            )

            for (sub in 0 until numOrdersFromPattern) {
                val newParsedOrder = ParsedOrder(
                    orderType = orderType,
                    storeName = storeName,
                    itemCount = itemsInThisOrderLeg,
                    isItemCountEstimated = isItemCountEstimated,
                    badges = orderBadgesFound,
                    orderIndex = currentGlobalOrderIndex
                )
                parsedOrdersResult.add(newParsedOrder)
                Log.i(TAG, "Added ParsedOrder ($currentGlobalOrderIndex): $newParsedOrder")
                currentGlobalOrderIndex++
            }
        }

        // --- Reconciliation with totalOrders (heuristic from OfferParser) ---
        val ordersActuallyParsedCount = parsedOrdersResult.size
        Log.d(
            TAG,
            "Initially parsed $ordersActuallyParsedCount orders (including sub-orders from patterns). Heuristic totalOrders: $totalOrders"
        )
        if (ordersActuallyParsedCount < totalOrders && parsedOrdersResult.isNotEmpty()) {
            val ordersToPotentiallyAdd = totalOrders - ordersActuallyParsedCount
            Log.i(
                TAG,
                "Heuristic suggests $ordersToPotentiallyAdd more orders. Duplicating last known order."
            )
            val lastParsedOrderTemplate = parsedOrdersResult.last()

            for (i in 0 until ordersToPotentiallyAdd) {
                val duplicatedOrder = lastParsedOrderTemplate.copy(
                    orderIndex = currentGlobalOrderIndex
                )
                parsedOrdersResult.add(duplicatedOrder)
                Log.i(
                    TAG,
                    "Added duplicated ParsedOrder ($currentGlobalOrderIndex): $duplicatedOrder"
                )
                currentGlobalOrderIndex++
            }
        } else if (parsedOrdersResult.isEmpty() && totalOrders > 0) {
            Log.w(
                TAG,
                "No orders were parsed from headers, but heuristicTotalOrders ($totalOrders) > 0. Not creating ghost orders."
            )
        }

        Log.i(TAG, "parseOrders finished. Returning ${parsedOrdersResult.size} orders.")
        Log.v(TAG, "Final ParsedOrders list: $parsedOrdersResult")
        return parsedOrdersResult
    }
}
