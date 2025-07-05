// Create a new file: app/src/main/java/cloud/trotter/dashbuddy/state/handlers/OrderDelivered.kt

package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.order.OrderStatus
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen

class OrderDelivered : StateHandler {
    private val tag = this::class.simpleName ?: "OrderDeliveredHandler"
    private val orderRepo = DashBuddyApplication.orderRepo
    private val currentRepo = DashBuddyApplication.currentRepo

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "--- Entering $tag ---")
        val activeOrderId = stateContext.currentDashState?.activeOrderId
        if (activeOrderId != null) {
            orderRepo.updateOrderStatus(activeOrderId, OrderStatus.DROPOFF_CONFIRMED)
            Log.i(tag, "Order ID $activeOrderId marked as DROPOFF_CONFIRMED.")
            // After delivery, the order is no longer the "active" one
            currentRepo.removeOrderFromQueue(activeOrderId)
        } else {
            Log.w(tag, "Cannot mark order as delivered, activeOrderId is null.")
        }
    }

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        // After a delivery, we typically see the "Delivery Completed" dialog
        // or go back to the map to await a new offer.
        return when (stateContext.screenInfo?.screen) {
            Screen.DELIVERY_COMPLETED_DIALOG -> AppState.DASH_ACTIVE_DELIVERY_COMPLETED
            Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> AppState.DASH_ACTIVE_AWAITING_OFFER
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