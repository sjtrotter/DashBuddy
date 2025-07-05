package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler

class OnNavigation : StateHandler {
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        // Nothing to do on entrance.
    }

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        // we could be either in a pickup, delivery, or generic nav state.
        // we transition to onPickup or onDelivery based on the screen.
        // we can also exit into a popped-up offer, back to main map idle or
        // dash along the way.  but i think that's it.

        // if we don't have a screen, we can't make any decisions.
        val screen = stateContext.screenInfo?.screen ?: return currentState
        return when {
            screen == Screen.OFFER_POPUP -> AppState.DASH_ACTIVE_OFFER_PRESENTED
            screen == Screen.MAIN_MAP_IDLE || screen == Screen.ON_DASH_ALONG_THE_WAY
                -> AppState.DASH_ACTIVE_AWAITING_OFFER

            screen.isPickup -> AppState.DASH_ACTIVE_ON_PICKUP
            screen.isDelivery -> AppState.DASH_ACTIVE_ON_DELIVERY
            else -> currentState
        }
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        // nothing to do on exit.
    }
}
