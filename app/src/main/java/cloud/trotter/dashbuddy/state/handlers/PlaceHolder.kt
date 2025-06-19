package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler

// You can create more specific placeholder handlers or a generic one:
class PlaceHolder : StateHandler {
    override fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
    }

    override fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState = currentState

    override fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
    }
}