package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.log.Logger as Log

class PostDelivery : StateHandler {
    private val tag = "PostDeliveryHandler"

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        val screen = stateContext.screenInfo?.screen

        // 1. IGNORE the summary screen.
        // We just recorded it. We sit here until the user dismisses it.
        if (screen == Screen.DELIVERY_COMPLETED_DIALOG) {
            return currentState
        }

        // 2. Watch for the Next Step
        return when {
            screen == Screen.OFFER_POPUP -> AppState.DASH_ACTIVE_OFFER_PRESENTED

            // "Map" or "Waiting" means we are back in the pool
            screen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER ||
                    screen == Screen.ON_DASH_ALONG_THE_WAY ||
                    screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_ACTIVE_AWAITING_OFFER

            // Edge Case: Stacked Order? If we have another pickup/dropoff immediately
            screen?.isPickup == true -> AppState.DASH_ACTIVE_ON_PICKUP
            screen?.isDelivery == true -> AppState.DASH_ACTIVE_ON_DELIVERY

            else -> currentState
        }
    }

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "Entered Post-Delivery Latch. Waiting for user to dismiss summary...")
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "Exiting Post-Delivery. Next stop: $nextState")
    }
}