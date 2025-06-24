package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
//import cloud.trotter.dashbuddy.state.processing.CustomerProcessor
//import cloud.trotter.dashbuddy.state.processing.StoreProcessor
import cloud.trotter.dashbuddy.state.screens.Screen

/**
 * Manages the entire pickup phase, from offer acceptance to confirming the last pickup.
 * It acts as a dispatcher, reacting to whatever screen the Dasher app shows.
 */
class OnDelivery : StateHandler {

    private val tag = this::class.simpleName ?: "OnDeliveryHandler"

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "--- Delivery Phase Initiated ---")
        // Initial processing can happen here if needed, but the real work is in processEvent.
        processEvent(stateContext, currentState)
    }

    override suspend fun processEvent(stateContext: StateContext, currentState: AppState): AppState {
        Log.d(tag, "Processing screen: ${stateContext.dasherScreen?.name}")

        // This handler is a dispatcher. It looks at the screen and decides what to do.
        when (stateContext.dasherScreen) {

            // It's a navigation screen with store info
//            Screen.NAVIGATION_TO_STORE -> {
//                StoreProcessor.processSecondaryStoreScreen(stateContext) // Use the "focus-only" processor
//            }

            // It's the primary pickup details screen
//            Screen.DELIVERY_DETAILS_VIEW -> {
//                StoreProcessor.processPrimaryStoreScreen(stateContext) // Use the "full-processing" processor
//            }

            // It's a screen that shows the customer's name (e.g., after arriving)
//            Screen.PICKUP_WITH_CUSTOMER_NAME -> {
//                CustomerProcessor.processCustomerScreen(stateContext) // A new processor for customers
//            }

            // The user has tapped "Confirm Pickup"
//            Screen.CONFIRM_PICKUP -> {
//                // 1. Update the focused order's status to PICKED_UP
//                val focusedOrderId = ...
//                orderRepo.updateOrderStatus(focusedOrderId, OrderStatus.PICKED_UP)
//
//                // 2. Check if any more pickups remain in the active queue
//                val remainingPickups = ...
//                if (remainingPickups == 0) {
//                    // All pickups are done! End this meta-state and move to the delivery phase.
//                    Log.i(tag, "--- All Pickups Completed. Transitioning to Delivery Phase. ---")
//                    return AppState.SESSION_ACTIVE_ON_DELIVERY
//                }
//            }

            // The user is just looking at the map or dash controls; we do nothing and stay in this state.
//            Screen.ONLINE_MAP, Screen.DASH_CONTROL -> {
//                // No state change needed.
//            }

            else -> {
                // An unknown screen appeared, just wait.
            }
        }

        val screen = stateContext.screenInfo?.screen ?: return currentState
        // Determine next state based on screen changes
        return when {
            // TODO: below - cancelled order? why would we get to waiting for offer from here?
//            screen == Screen.ON_DASH_ALONG_THE_WAY -> AppState.SESSION_ACTIVE_DASHING_ALONG_THE_WAY
//            screen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
            // TODO: do we need to transition on dash control anymore?
//            screen == Screen.DASH_CONTROL -> AppState.VIEWING_DASH_CONTROL
            // TODO: hm.. if the dash ends right away, we *could* transition to this state.
            screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_IDLE_OFFLINE
//            screen == Screen.NAVIGATION_VIEW -> AppState.VIEWING_NAVIGATION
//              screen == Screen.PICKUP_DETAILS_PRE_ARRIVAL -> AppState.VIEWING_PICKUP_DETAILS
            screen.isPickup -> AppState.DASH_ACTIVE_ON_PICKUP
            else -> currentState
        }
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "--- Delivery Phase Concluded ---")
    }
}