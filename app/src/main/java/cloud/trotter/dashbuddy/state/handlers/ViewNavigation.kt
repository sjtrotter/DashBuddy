package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen

class ViewNavigation : StateHandler {

    override suspend fun processEvent(stateContext: StateContext, currentState: AppState): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")
        // process event here

        // add more specific things up here if needed.

        return when (stateContext.dasherScreen) {
            Screen.MAIN_MENU_VIEW -> AppState.VIEWING_MAIN_MENU
            Screen.MAIN_MAP_IDLE -> AppState.DASHER_ENDING_DASH_SESSION
            Screen.ON_DASH_ALONG_THE_WAY -> AppState.SESSION_ACTIVE_DASHING_ALONG_THE_WAY
            Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
            Screen.PICKUP_DETAILS_PRE_ARRIVAL -> AppState.VIEWING_PICKUP_DETAILS
            else -> currentState
        }
    }

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d("${this::class.simpleName} State", "Entering state...")
        // initialize components here
//        DashBuddyApplication.sendBubbleMessage("${currentState.displayName} State\n${context.dasherScreen?.screenName} Screen")
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}