package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.state.AppState as AppState

/**
 * The StateHandler classes this interface defines will in-turn define
 * the state of the doordash driver app. They are used to parse the event
 * texts or other data to determine if the state should be entered into
 * via the [processEvent] method, and it uses the [enterState] and
 * [exitState] methods to enter into and exit from this state, respectively.
 */
interface StateHandler {

    /**
     * The definition for when to enter this state.
     * @param stateContext the [StateContext] calling this handler.
     * @return this StateHandler instance if matched, null if not.
     */
    fun processEvent(stateContext: StateContext, currentState: AppState): AppState

    /**
     * The actions for entering this state.
     * @param stateContext the [StateContext] calling this handler.
     */
    fun enterState(stateContext: StateContext, currentState: AppState, previousState: AppState?)

    /**
     * The actions for exiting this state.
     * @param stateContext the [StateContext] calling this handler.
     */
    fun exitState(stateContext: StateContext, currentState: AppState, nextState: AppState)

}
