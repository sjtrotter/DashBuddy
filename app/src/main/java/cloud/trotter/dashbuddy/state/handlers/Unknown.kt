package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler

// Placeholder/Example handlers for states not yet implemented by you
// You would replace these with your actual handler classes.
class Unknown : StateHandler {
    override fun enterState(
        context: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
    }

    override fun processEvent(
        context: StateContext,
        currentState: AppState
    ): AppState = currentState

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {}
}
