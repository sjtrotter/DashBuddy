package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen

class ViewTimeline : StateHandler {

    override suspend fun processEvent(stateContext: StateContext, currentState: AppState): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")
        // process event here

        // add more specific things up here if needed.

        if (stateContext.dasherScreen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER) return AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
        if (stateContext.dasherScreen == Screen.ON_DASH_ALONG_THE_WAY) return AppState.SESSION_ACTIVE_DASHING_ALONG_THE_WAY
        if (stateContext.dasherScreen == Screen.DASH_CONTROL) return AppState.VIEWING_DASH_CONTROL

        return currentState
    }

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d("${this::class.simpleName} State", "Entering state...")
        // initialize components here
        DashBuddyApplication.sendBubbleMessage("${currentState.displayName} State\n${stateContext.dasherScreen?.screenName} Screen")
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}