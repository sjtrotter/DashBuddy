package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.state.Manager
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen
import kotlinx.coroutines.launch

class DashStopping : StateHandler {

    private val currentRepo = DashBuddyApplication.currentRepo
    private val dashRepo = DashBuddyApplication.dashRepo

    override fun processEvent(context: StateContext, currentState: AppState): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")
        // process event here

        // Like DashStarting, the whole point is to end the dash in enterState.
        // After, here, we just check the screen and transition to the next state.
        if (context.dasherScreen == Screen.MAIN_MAP_IDLE) return AppState.DASHER_IDLE_OFFLINE

        return currentState
    }

    override fun enterState(
        context: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {

        Log.d("${this::class.simpleName} State", "Entering state...")

        Manager.getScope().launch {
            // Get current first.
            val current: CurrentEntity? = currentRepo.getCurrentDashState()

            // Get current dash.
            var dash: DashEntity? = null
            if (current?.dashId != null) {
                dash = dashRepo.getDashById(current.dashId)
            } else {
                Log.e(
                    "${this::class.simpleName} State",
                    "Current dash ID is null; can't finalize dash."
                )
            }
            if (dash != null) {
                val endedDash = dash.copy(
                    stopTime = System.currentTimeMillis(),
                    duration = System.currentTimeMillis() - dash.startTime,
                    // need to calculate for other columns.
                )
                dashRepo.updateDash(endedDash)
            } else {
                Log.e("${this::class.simpleName} State", "Dash is null; can't finalize dash.")
            }

            if (current?.isActive == false) Log.w(
                "${this::class.simpleName} State",
                "Warning -- current dash is not active."
            )
            // Update (clear) the Current table.
            currentRepo.clearCurrentDashState()
        }
        DashBuddyApplication.sendBubbleMessage("${currentState.displayName} State\n${context.dasherScreen?.screenName} Screen")
    }

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}