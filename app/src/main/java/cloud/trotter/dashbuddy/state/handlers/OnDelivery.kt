package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen

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

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        Log.d(tag, "Processing screen: ${stateContext.screenInfo?.screen?.name}")

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
            screen == Screen.OFFER_POPUP -> AppState.DASH_ACTIVE_OFFER_PRESENTED
            screen == Screen.DELIVERY_COMPLETED_DIALOG -> AppState.DASH_ACTIVE_DELIVERY_COMPLETED

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