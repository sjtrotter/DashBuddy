package cloud.trotter.dashbuddy.state.handlers

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.event.DashEventEntity
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen

class DashStarting : StateHandler {

    private val tag = "DashStarting"

    private val currentRepo = DashBuddyApplication.currentRepo
    private val zoneRepo = DashBuddyApplication.zoneRepo

    // Assumes you've added this to DashBuddyApplication
    private val dashEventRepo = DashBuddyApplication.dashEventRepo

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        // Transition logic remains the same
        return when (stateContext.screenInfo?.screen) {
            Screen.ON_DASH_MAP_WAITING_FOR_OFFER, Screen.ON_DASH_ALONG_THE_WAY ->
                AppState.DASH_ACTIVE_AWAITING_OFFER

            Screen.OFFER_POPUP ->
                AppState.DASH_ACTIVE_OFFER_PRESENTED

            Screen.DASH_SUMMARY_SCREEN, Screen.MAIN_MAP_IDLE ->
                AppState.DASH_STOPPING

            else -> currentState
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "Entering state: Initializing new dash session (Event Logging).")

        try {
            // 1. Get Context (Zone/Type) from Staging
            val currentInfo = currentRepo.getCurrentDashState()
            val zoneId = currentInfo?.zoneId

            if (zoneId == null) {
                Log.e(tag, "Cannot start dash: Zone ID is null.")
                DashBuddyApplication.sendBubbleMessage("Error: Zone not set.")
                return
            }

            // Get names for UI/Logging
            val zone = zoneRepo.getZoneById(zoneId)
            val zoneName = zone?.zoneName ?: "Unknown Zone"
            val dashType = currentInfo.dashType?.toString() ?: "Unknown"

            // 2. Log the START Event
            val startEvent = DashEventEntity(
                type = "START",
                timestamp = stateContext.timestamp,
                zoneId = zoneId,
                dashType = dashType,
                odometerReading = stateContext.odometerReading
            )
            dashEventRepo.insert(startEvent)
            Log.i(tag, "Dash START event logged for zone: $zoneName")

            // 3. Start Location Service (Mileage Tracking)
            val serviceIntent = Intent(DashBuddyApplication.context, LocationService::class.java)
            ContextCompat.startForegroundService(DashBuddyApplication.context, serviceIntent)

            // 4. Send Success Message
            DashBuddyApplication.sendBubbleMessage("Dashing in\n$zoneName")

        } catch (e: Exception) {
            Log.e(tag, "Critical error starting dash", e)
            DashBuddyApplication.sendBubbleMessage("Error starting dash!")
        }
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "Exiting DashStarting -> $nextState")
    }
}