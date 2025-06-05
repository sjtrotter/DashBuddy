package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen

class ViewNavigation : StateHandler {

    override fun processEvent(context: StateContext, currentState: AppState): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")
        // process event here

        // add more specific things up here if needed.

        if (context.dasherScreen == Screen.MAIN_MENU_VIEW) return AppState.VIEWING_MAIN_MENU
        if (context.dasherScreen == Screen.MAIN_MAP_IDLE) return AppState.DASHER_ENDING_DASH_SESSION
        if (context.dasherScreen == Screen.ON_DASH_ALONG_THE_WAY) return AppState.SESSION_ACTIVE_DASHING_ALONG_THE_WAY
        if (context.dasherScreen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER) return AppState.SESSION_ACTIVE_WAITING_FOR_OFFER

        return currentState
    }

    override fun enterState(context: StateContext, currentState: AppState, previousState: AppState?) {
        Log.d("${this::class.simpleName} State", "Entering state...")
        // initialize components here
//        DashBuddyApplication.sendBubbleMessage("${currentState.displayName} State\n${context.dasherScreen?.screenName} Screen")
    }

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}