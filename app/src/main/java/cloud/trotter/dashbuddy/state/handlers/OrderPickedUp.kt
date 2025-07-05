// Create a new file: app/src/main/java/cloud/trotter/dashbuddy/state/handlers/OrderPickedUp.kt

package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.order.OrderStatus
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen

class OrderPickedUp : StateHandler {
    private val tag = this::class.simpleName ?: "OrderPickedUpHandler"
    private val orderRepo = DashBuddyApplication.orderRepo

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "--- Entering $tag ---")
        val activeOrderId = stateContext.currentDashState?.activeOrderId
        if (activeOrderId != null) {
            orderRepo.updateOrderStatus(activeOrderId, OrderStatus.PICKUP_CONFIRMED)
            Log.i(tag, "Order ID $activeOrderId marked as PICKUP_CONFIRMED.")
            // You can also send a bubble message here if you want
            // DashBuddyApplication.sendBubbleMessage("Order Picked Up!")
        } else {
            Log.w(tag, "Cannot mark order as picked up, activeOrderId is null.")
        }
    }

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        // After pickup, we should be navigating to the delivery
        if (stateContext.screenInfo?.screen?.isDelivery == true) {
            return AppState.DASH_ACTIVE_ON_DELIVERY
        }
        // Fallback in case the screen doesn't immediately change
        return currentState
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "--- Exiting $tag ---")
    }
}