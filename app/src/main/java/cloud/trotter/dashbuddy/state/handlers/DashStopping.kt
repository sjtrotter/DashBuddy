package cloud.trotter.dashbuddy.state.handlers

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.event.DashEventEntity
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen

class DashStopping : StateHandler {

    private val tag = "DashStopping"

    private val currentRepo = DashBuddyApplication.currentRepo
    private val zoneRepo = DashBuddyApplication.zoneRepo
    private val dashEventRepo = DashBuddyApplication.dashEventRepo

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        // Transition logic
        if (stateContext.screenInfo?.screen == Screen.MAIN_MAP_IDLE) {
            return AppState.DASH_IDLE_OFFLINE
        }
        return currentState
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "Entering state: Stopping dash session.")

        try {
            // 1. Get Context for Logging
            val current = currentRepo.getCurrentDashState()
            var zoneName = "Unknown Zone"

            if (current?.zoneId != null) {
                val zone = zoneRepo.getZoneById(current.zoneId)
                zoneName = zone?.zoneName ?: "Unknown Zone"
            }

            // 2. Log the STOP Event
            // We record the current odometer so we can calculate total miles (Stop Odo - Start Odo) later.
            val stopEvent = DashEventEntity(
                type = "STOP",
                timestamp = stateContext.timestamp,
                zoneId = current?.zoneId,
                dashType = current?.dashType?.toString(),
                odometerReading = stateContext.odometerReading
            )
            dashEventRepo.insert(stopEvent)
            Log.i(tag, "Dash STOP event logged.")

            // 3. Stop Location Service
            val serviceIntent = Intent(DashBuddyApplication.context, LocationService::class.java)
            DashBuddyApplication.context.stopService(serviceIntent)

            // 4. Notify User
            DashBuddyApplication.sendBubbleMessage("Dash Ended\n$zoneName")

            // 5. Cleanup Staging Data
            // We clear the CurrentRepo so the app knows we aren't in a session anymore.
            currentRepo.clearCurrentDashState()

        } catch (e: Exception) {
            Log.e(tag, "Critical error stopping dash", e)
            DashBuddyApplication.sendBubbleMessage("Error stopping dash!")

            // Ensure we at least try to reset the state
            currentRepo.clearCurrentDashState()
        }
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "Exiting DashStopping -> $nextState")
    }
}