package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.state.StateManager
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler

class Startup : StateHandler {

    private val currentRepo = DashBuddyApplication.currentRepo

    override fun processEvent(stateContext: StateContext, currentState: AppState): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")

        return currentState
    }

    override fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d("${this::class.simpleName} State", "Entering state...")
        StateManager.enqueueDbWork {
            try {
                // Get the current state from the DB, or create a default empty one
                currentRepo.upsertCurrentDashState(CurrentEntity())
            } catch (e: Exception) {
                Log.e("Startup", "!!! Error initializing DashBuddy. !!!", e)
            }
            DashBuddyApplication.sendBubbleMessage("DashBuddy started!")
        }
    }

    override fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}