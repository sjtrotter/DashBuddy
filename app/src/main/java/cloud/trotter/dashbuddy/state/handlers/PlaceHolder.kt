package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler

// You can create more specific placeholder handlers or a generic one:
class PlaceHolder : StateHandler {
    override fun enterState(context: StateContext, currentState: AppState, previousState: AppState?) {}
    override fun processEvent(context: StateContext, currentState: AppState): AppState = currentState // Stays in current state
    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {}
}