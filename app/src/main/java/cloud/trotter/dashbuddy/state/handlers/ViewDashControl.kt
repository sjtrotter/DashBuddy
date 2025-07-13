package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen

class ViewDashControl : StateHandler {

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")
        // process event here

        // add more specific things up here if needed.

        val screen = stateContext.screenInfo?.screen ?: return currentState
        return when {
            screen == Screen.ON_DASH_ALONG_THE_WAY ||
                    screen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER ->
                AppState.DASH_ACTIVE_AWAITING_OFFER

            screen == Screen.OFFER_POPUP -> AppState.DASH_ACTIVE_OFFER_PRESENTED
            screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_IDLE_OFFLINE
            screen == Screen.TIMELINE_VIEW -> AppState.DASH_ACTIVE_ON_TIMELINE

            screen.isPickup -> AppState.DASH_ACTIVE_ON_PICKUP
            screen.isDelivery -> AppState.DASH_ACTIVE_ON_DELIVERY

            else -> currentState
        }
    }

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d("${this::class.simpleName} State", "Entering state...")
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}