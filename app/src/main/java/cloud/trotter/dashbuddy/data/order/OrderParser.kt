package cloud.trotter.dashbuddy.data.order // Assuming it belongs in this package

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
//import cloud.trotter.dashbuddy.log.Logger as Log
import kotlin.math.roundToInt

@Singleton
class OrderParser @Inject constructor() {

    // Regexes for parsing details within an order's text block
    private val itemCountSimpleRegex = Regex("""\((\d+)\s*(?:item|items)\)""") // For (X items)
    private val stackedOrderItemCountRegex = Regex(
        """\((\d+)\s*orders?,\s*(\d+)\s*items\)| \((\d+)\s*items,\s*(\d+)\s*orders?\)""",
        RegexOption.IGNORE_CASE
    ) // For (X orders, Y items)
    private val multiOrderOnlyRegex = Regex("""\((\d+)\s*orders?\)""") // For just (X orders)

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
        Timber.d(
            "parseOrders called. Region texts count: ${ordersRegionTexts.size}, Heuristic totalOrders: $totalOrders"
        )
        Timber.v("OrdersRegionTexts: $ordersRegionTexts") // Verbose log for full text

        val parsedOrdersResult = mutableListOf<ParsedOrder>()
        if (ordersRegionTexts.isEmpty()) {
            if (totalOrders > 0) {
                Timber.w(
                    "Orders region is empty, but heuristicTotalOrders ($totalOrders) > 0. Returning empty list."
                )
            } else {
                Timber.d("Orders region is empty. Returning empty list.")
            }
            return parsedOrdersResult
        }

        val orderHeaderIndicesInRegion = ordersRegionTexts.mapIndexedNotNull { index, text ->
            if (OrderType.fromTypeName(text.trim()) != null) index else null
        }
        Timber.d(
            "Found ${orderHeaderIndicesInRegion.size} order headers at indices: $orderHeaderIndicesInRegion"
        )

        var currentGlobalOrderIndex = 0

        orderHeaderIndicesInRegion.forEachIndexed { idx, headerIndexInRegion ->
            Timber.d("Processing order header #$idx at region index: $headerIndexInRegion")
            val orderType = OrderType.fromTypeName(ordersRegionTexts[headerIndexInRegion].trim())!!
            Timber.d("OrderType: ${orderType.typeName}")

            val storeNameIndexInRegion = headerIndexInRegion + 1
            if (storeNameIndexInRegion >= ordersRegionTexts.size) {
                Timber.w(
                    "Malformed order block: OrderType found at end of region texts, no store name. Skipping."
                )
                return@forEachIndexed
            }

            val storeName = ordersRegionTexts[storeNameIndexInRegion].trim()
            Timber.d("StoreName: $storeName")

            val nextOrderHeaderInRegion = orderHeaderIndicesInRegion.getOrNull(idx + 1)
            val detailsEndIndexInRegion = nextOrderHeaderInRegion ?: ordersRegionTexts.size
            Timber.d(
                "Detail texts for this order will be from region index ${storeNameIndexInRegion + 1} to $detailsEndIndexInRegion"
            )

            val detailTextsForThisOrder =
                if (storeNameIndexInRegion + 1 < detailsEndIndexInRegion) {
                    ordersRegionTexts.subList(storeNameIndexInRegion + 1, detailsEndIndexInRegion)
                } else {
                    emptyList()
                }
            Timber.v("DetailTextsForThisOrder: $detailTextsForThisOrder")

            var itemsInThisOrderLeg = if (orderType.isShoppingOrder) 0 else 1
            var isItemCountEstimated = false
            var numOrdersFromPattern = 1
            val orderBadgesFound: Set<OrderBadge> =
                OrderBadge.findAllBadgesInOrderBlock(detailTextsForThisOrder)
            Timber.d("OrderSpecificBadges found for this order: $orderBadgesFound")
            var patternMatchedThisOrder = false

            detailTextsForThisOrder.forEach { detailText ->
                Timber.v("Scanning detailText: '$detailText'")
                if (!patternMatchedThisOrder) {
                    val stackedMatch = stackedOrderItemCountRegex.find(detailText)
                    if (stackedMatch != null) {
                        Timber.d("STACKED_ORDER_ITEM_COUNT_REGEX matched: $detailText")
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
                        Timber.d(
                            "Parsed from stacked: numOrders=$numOrdersFromPattern, itemsPerOrder=$itemsInThisOrderLeg (estimated)"
                        )
                    } else {
                        multiOrderOnlyRegex.find(detailText)?.groupValues?.get(1)?.toIntOrNull()
                            ?.let {
                                Timber.d("MULTI_ORDER_ONLY_REGEX matched: $detailText")
                                numOrdersFromPattern = it
                                itemsInThisOrderLeg = 1
                                isItemCountEstimated = false
                                patternMatchedThisOrder = true
                                Timber.d(
                                    "Parsed from multi-order: numOrders=$numOrdersFromPattern, itemsPerOrder=$itemsInThisOrderLeg"
                                )
                            }
                        if (!patternMatchedThisOrder) {
                            itemCountSimpleRegex.find(detailText)?.groupValues?.get(1)
                                ?.toIntOrNull()?.let {
                                    Timber.d("ITEM_COUNT_SIMPLE_REGEX matched: $detailText")
                                    itemsInThisOrderLeg = it
                                    patternMatchedThisOrder = true
                                    Timber.d(
                                        "Parsed from simple item count: items=$itemsInThisOrderLeg"
                                    )
                                }
                        }
                    }
                }
            }

            if (orderType.isShoppingOrder && !patternMatchedThisOrder && itemsInThisOrderLeg == 0) {
                itemsInThisOrderLeg = 0
                Timber.d("Shop order, no item pattern matched, itemsInThisOrderLeg remains 0.")
            }
            Timber.d(
                "Final for this leg: items=$itemsInThisOrderLeg, numSubOrders=$numOrdersFromPattern, estimated=$isItemCountEstimated"
            )

            (0 until numOrdersFromPattern).forEach { _ ->
                val newParsedOrder = ParsedOrder(
                    orderType = orderType,
                    storeName = storeName,
                    itemCount = itemsInThisOrderLeg,
                    isItemCountEstimated = isItemCountEstimated,
                    badges = orderBadgesFound,
                    orderIndex = currentGlobalOrderIndex
                )
                parsedOrdersResult.add(newParsedOrder)
                Timber.i("Added ParsedOrder ($currentGlobalOrderIndex): $newParsedOrder")
                currentGlobalOrderIndex++
            }
        }

        // --- Reconciliation with totalOrders (heuristic from OfferParser) ---
        val ordersActuallyParsedCount = parsedOrdersResult.size
        Timber.d(
            "Initially parsed $ordersActuallyParsedCount orders (including sub-orders from patterns). Heuristic totalOrders: $totalOrders"
        )
        if (ordersActuallyParsedCount < totalOrders && parsedOrdersResult.isNotEmpty()) {
            val ordersToPotentiallyAdd = totalOrders - ordersActuallyParsedCount
            Timber.i(
                "Heuristic suggests $ordersToPotentiallyAdd more orders. Duplicating last known order."
            )
            val lastParsedOrderTemplate = parsedOrdersResult.last()

            (0 until ordersToPotentiallyAdd).forEach { _ ->
                val duplicatedOrder = lastParsedOrderTemplate.copy(
                    orderIndex = currentGlobalOrderIndex
                )
                parsedOrdersResult.add(duplicatedOrder)
                Timber.i(
                    "Added duplicated ParsedOrder ($currentGlobalOrderIndex): $duplicatedOrder"
                )
                currentGlobalOrderIndex++
            }
        } else if (parsedOrdersResult.isEmpty() && totalOrders > 0) {
            Timber.w(
                "No orders were parsed from headers, but heuristicTotalOrders ($totalOrders) > 0. Not creating ghost orders."
            )
        }

        Timber.i("parseOrders finished. Returning ${parsedOrdersResult.size} orders.")
        Timber.v("Final ParsedOrders list: $parsedOrdersResult")
        return parsedOrdersResult
    }
}
