package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler

// Placeholder/Example handlers for states not yet implemented by you
// You would replace these with your actual handler classes.
class Unknown : StateHandler {
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
    }

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState = currentState

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
    }
}
