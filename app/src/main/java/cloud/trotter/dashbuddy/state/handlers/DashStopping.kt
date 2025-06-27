package cloud.trotter.dashbuddy.state.handlers

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.zone.ZoneEntity
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.dasher.screen.Screen

class DashStopping : StateHandler {

    private val tag = this::class.simpleName ?: "DashStopping"

    // Assuming these are correctly initialized via DashBuddyApplication
    private val currentRepo = DashBuddyApplication.currentRepo
    private val dashRepo = DashBuddyApplication.dashRepo
    private val zoneRepo = DashBuddyApplication.zoneRepo // Added ZoneRepository

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        Log.d(tag, "Evaluating state for event...")

        // The primary logic of this state is in enterState.
        // Here, we just transition if the screen changes after the dash has been stopped.
        if (stateContext.screenInfo?.screen == Screen.MAIN_MAP_IDLE) {
            Log.i(tag, "Screen is MAIN_MAP_IDLE, transitioning to DASHER_IDLE_OFFLINE.")
            return AppState.DASH_IDLE_OFFLINE
        }
        // Potentially handle other screen changes if the app doesn't go directly to idle.
        // For example, if it shows a dash summary screen first.
        // if (context.dasherScreen == Screen.DASH_SUMMARY) return AppState.VIEWING_DASH_SUMMARY

        return currentState // Stay in DashStopping if screen hasn't settled to MAIN_MAP_IDLE yet
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "Entering state: Attempting to stop and finalize current dash.")

        var currentZoneName: String?
        try {
            // Get current dash state first
            val current: CurrentEntity? = currentRepo.getCurrentDashState()

            if (current?.dashId == null) {
                Log.e(
                    tag,
                    "Cannot finalize dash: current dashId is null. CurrentEntity: $current"
                )
                DashBuddyApplication.sendBubbleMessage("Error: No active dash to stop.")
                // Clear current state anyway, as it might be invalid
                currentRepo.clearCurrentDashState()
                Log.i(tag, "Cleared current dash state due to missing dashId.")
                return
            }
            Log.d(tag, "Current dash state retrieved: $current")

            // Fetch Zone Name for bubble message (optional, but good for user feedback)
            if (current.zoneId != null) {
                try {
                    val zone: ZoneEntity? = zoneRepo.getZoneById(current.zoneId)
                    currentZoneName = zone?.zoneName ?: "Unknown Zone"
                    Log.d(
                        tag,
                        "Current zone name: $currentZoneName for zoneId: ${current.zoneId}"
                    )
                } catch (ze: Exception) {
                    Log.w(tag, "Could not retrieve zone name for zoneId: ${current.zoneId}", ze)
                    currentZoneName = "Zone Error"
                }
            } else {
                Log.w(tag, "zoneId is null in CurrentEntity, cannot fetch zone name.")
                currentZoneName = "N/A Zone"
            }

            DashBuddyApplication.sendBubbleMessage(
                "Ending Dash in $currentZoneName"
            )

            // Get the DashEntity to update
            val dash: DashEntity? = dashRepo.getDashById(current.dashId)

            if (dash != null) {
                Log.d(tag, "Dash to finalize found: $dash")
                val stopTime = stateContext.timestamp
                val duration = if (dash.startTime > 0) stopTime - dash.startTime else 0L

                val endedDash = dash.copy(
                    stopTime = stopTime,
                    totalTime = duration
                    // TODO: Calculate other summary columns for DashEntity if needed
                    // e.g., totalOffers, acceptedOffers, totalPayout (might require querying offers)
                )
                Log.d(tag, "Updating dash with final details: $endedDash")
                dashRepo.updateDash(endedDash)
                Log.i(tag, "Dash ID ${dash.id} successfully updated and finalized.")
            } else {
                Log.e(
                    tag,
                    "DashEntity not found for dashId: ${current.dashId}. Cannot finalize."
                )
                // Still proceed to clear current state as it refers to a non-existent dash
            }

            // Always clear the Current table after attempting to stop a dash
            Log.i(tag, "Clearing current dash state from CurrentEntity table.")
            currentRepo.clearCurrentDashState()
            Log.i(tag, "Current dash state cleared.")

            // Optionally, send a success bubble message
            // DashBuddyApplication.sendBubbleMessage("Dash Ended\nZone: $currentZoneName")

        } catch (e: Exception) {
            Log.e(tag, "!!! CRITICAL ERROR during dash stopping process !!!", e)
            DashBuddyApplication.sendBubbleMessage("Error stopping dash!\nCheck logs.")
            // Even on error, try to clear current state to prevent inconsistent states
            try {
                Log.w(tag, "Attempting to clear current dash state after error.")
                currentRepo.clearCurrentDashState()
            } catch (clearError: Exception) {
                Log.e(
                    tag,
                    "!!! Failed to clear current dash state after initial error !!!",
                    clearError
                )
            }
        }
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "Exiting state to $nextState.")
        // Any cleanup specific to exiting DashStopping, if necessary
    }
}
