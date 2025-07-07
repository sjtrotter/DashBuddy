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

        val screen = stateContext.screenInfo?.screen ?: return currentState

        return when {
            // after pickup, we *should* be on delivery.
            screen.isDelivery -> AppState.DASH_ACTIVE_ON_DELIVERY
            // but, we might have a stacked order.
            screen.isPickup -> AppState.DASH_ACTIVE_ON_PICKUP
            // or, maybe something went wrong and we're on the map.
            screen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> AppState.DASH_ACTIVE_AWAITING_OFFER
            else -> currentState

        }
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "--- Exiting $tag ---")
    }
}