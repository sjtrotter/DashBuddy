package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen

class SetDashEndTime : StateHandler {

    override fun processEvent(context: StateContext, currentState: AppState): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")
        // process event here

        // add more specific things up here if needed.

        if (context.dasherScreen == Screen.MAIN_MAP_IDLE) return AppState.DASHER_IDLE_OFFLINE
        if (context.dasherScreen == Screen.ON_DASH_ALONG_THE_WAY) return AppState.DASHER_INITIATING_DASH_SESSION
        if (context.dasherScreen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER) return AppState.DASHER_INITIATING_DASH_SESSION

        // if we decide to capture the initial end time, we should here. I don't think we need to.
        // also this isn't consistent because this only happens for "Dash Now" or "Dash Along the Way"
        // i.e. it's not for scheduled dashes. We'd need to capture the end time from the
        // DasherIdleOffline screen for scheduled dashes.

        return currentState
    }

    override fun enterState(context: StateContext, currentState: AppState, previousState: AppState?) {
        Log.d("${this::class.simpleName} State", "Entering state...")
        // initialize components here
        DashBuddyApplication.sendBubbleMessage("${currentState.displayName} State\n${context.dasherScreen?.screenName} Screen")
    }

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}