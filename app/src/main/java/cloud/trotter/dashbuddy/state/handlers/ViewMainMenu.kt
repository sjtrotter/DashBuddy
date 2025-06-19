package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen

class ViewMainMenu : StateHandler {

    override fun processEvent(stateContext: StateContext, currentState: AppState): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")

        if (stateContext.dasherScreen == Screen.EARNINGS_VIEW) return AppState.VIEWING_EARNINGS
        if (stateContext.dasherScreen == Screen.RATINGS_VIEW) return AppState.VIEWING_RATINGS
        if (stateContext.dasherScreen == Screen.SCHEDULE_VIEW) return AppState.VIEWING_SCHEDULE
        if (stateContext.dasherScreen == Screen.MAIN_MAP_IDLE) return AppState.DASHER_IDLE_OFFLINE
        if (stateContext.dasherScreen == Screen.DASH_CONTROL) return AppState.VIEWING_DASH_CONTROL

        return currentState
    }

    override fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d("${this::class.simpleName} State", "Entering state...")
//        DashBuddyApplication.sendBubbleMessage("${currentState.displayName} State\n${stateContext.dasherScreen?.screenName} Screen")
    }

    override fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}