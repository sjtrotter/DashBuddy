package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler

class DasherLogin : StateHandler {

    override suspend fun processEvent(stateContext: StateContext, currentState: AppState): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")
        // process event here

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