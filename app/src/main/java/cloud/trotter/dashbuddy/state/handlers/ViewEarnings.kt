package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen

class ViewEarnings : StateHandler {

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")
        // process event here

        // add more specific things up here if needed.

        if (stateContext.screenInfo?.screen == Screen.DASH_CONTROL) return AppState.DASH_ACTIVE_ON_CONTROL
        if (stateContext.screenInfo?.screen == Screen.MAIN_MAP_IDLE) return AppState.DASH_IDLE_OFFLINE
        if (stateContext.screenInfo?.screen == Screen.TIMELINE_VIEW) return AppState.DASH_ACTIVE_ON_TIMELINE
        if (stateContext.screenInfo?.screen == Screen.OFFER_POPUP) return AppState.DASH_ACTIVE_OFFER_PRESENTED

        return currentState
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