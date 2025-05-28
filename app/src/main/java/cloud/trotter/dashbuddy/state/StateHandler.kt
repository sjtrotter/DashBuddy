package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.state.App as AppState

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
     * @param context the [Context] calling this handler.
     * @return this StateHandler instance if matched, null if not.
     */
    fun processEvent(context: Context, currentState: AppState): AppState

    /**
     * The actions for entering this state.
     * @param context the [Context] calling this handler.
     */
    fun enterState(context: Context, currentState: AppState, previousState: AppState?)

    /**
     * The actions for exiting this state.
     * @param context the [Context] calling this handler.
     */
    fun exitState(context: Context, currentState: AppState, nextState: AppState)

}
