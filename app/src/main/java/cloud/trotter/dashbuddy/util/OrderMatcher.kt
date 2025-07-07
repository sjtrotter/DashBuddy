package cloud.trotter.dashbuddy.util

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.order.OrderEntity
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.StateContext

/**
 * An object which will take a [StateContext] and try to
 * match the screen to an order in the queue (or the active order).
 */
object OrderMatcher {
    // We need access to the Order repository to fetch order details for comparison
    private val orderRepo = DashBuddyApplication.orderRepo
    private val tag = this::class.simpleName ?: "OrderMatcher"

    /**
     * Attempts to find a matching order ID from the active queue based on screen context.
     *
     * @param stateContext The current state context containing screen info and dash state.
     * @return The matched [Long] order ID, or null if no match is found.
     */
    suspend fun matchOrder(stateContext: StateContext): Long? {
        Log.d(tag, "Attempting to match order with prioritized strategy...")

        val screenInfo = stateContext.screenInfo as? ScreenInfo.OrderDetails ?: return null.also {
            Log.w(tag, "Matcher failed: Screen info is not of type ScreenInfo.OrderDetails.")
        }
        val dashState = stateContext.currentDashState ?: return null.also {
            Log.w(tag, "Matcher failed: Dash state is null.")
        }

        val possibleOrderIds = buildList {
            dashState.activeOrderId?.let { add(it) }
            addAll(dashState.activeOrderQueue)
        }.distinct()

        if (possibleOrderIds.isEmpty()) return null.also {
            Log.w(tag, "Matcher failed: No active orders found in dash state: $possibleOrderIds")
        }

        // if there is only one order, that's it, we can just return it
        if (possibleOrderIds.size == 1) return possibleOrderIds.first().also {
            Log.d(tag, "Matcher found only one active order: $it. Returning it.")
        }

        val allPossibleOrders = possibleOrderIds.mapNotNull { orderRepo.getOrderById(it) }
        var eligibleOrders: List<OrderEntity> = emptyList()
        if (stateContext.screenInfo.screen.isPickup) {
            eligibleOrders = allPossibleOrders.filter { order ->
                !order.status.isPickedUp && !order.status.isDelivered &&
                        !order.status.isCancelled && !order.status.isUnassigned
            }
        } else if (stateContext.screenInfo.screen.isDelivery) {
            eligibleOrders = allPossibleOrders.filter { order ->
                order.status.isPickedUp && !order.status.isDelivered &&
                        !order.status.isCancelled && !order.status.isUnassigned
            }
        }
        if (eligibleOrders.isEmpty()) return null.also {
            Log.w(
                tag,
                "Matcher failed: No eligible orders found: ${stateContext.screenInfo}"
            )
        }
        Log.v(
            tag,
            "Found ${allPossibleOrders.size} total orders, ${eligibleOrders.size} are eligible for a pickup match."
        )

        // --- Level 1: The "Perfect Match" (Customer + Store) ---
        if (screenInfo.customerNameHash != null && screenInfo.storeName != null) {
            Log.v(tag, "Attempting Level 1 Match (Customer + Store)...")
            val perfectMatch = eligibleOrders.find { order ->
                order.customerNameHash == screenInfo.customerNameHash &&
                        UtilityFunctions.stringsMatch(order.storeName, screenInfo.storeName)
            }
            if (perfectMatch != null) {
                Log.i(tag, "SUCCESS (L1): Found perfect match for Order ID ${perfectMatch.id}")
                return perfectMatch.id
            }
        }

        // --- Level 2: The "Store-Only Match" (Fallback) ---
        if (screenInfo.storeName != null) {
            Log.v(tag, "Attempting Level 2 Match (Store Name only)...")
            val storeMatch = eligibleOrders.find { order ->
                UtilityFunctions.stringsMatch(order.storeName, screenInfo.storeName)
            }
            if (storeMatch != null) {
                Log.i(tag, "SUCCESS (L2): Found store-only match for Order ID ${storeMatch.id}")
                return storeMatch.id
            }
        }

        Log.w(tag, "Matcher failed: Could not find any match for the given screen info.")
        return null
    }
}