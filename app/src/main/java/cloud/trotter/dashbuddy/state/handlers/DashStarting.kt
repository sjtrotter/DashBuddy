package cloud.trotter.dashbuddy.state.handlers

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen

class DashStarting : StateHandler {

    private val tag = this::class.simpleName ?: "DashStarting"

    private val currentRepo = DashBuddyApplication.currentRepo
    private val dashRepo = DashBuddyApplication.dashRepo
    private val dashZoneRepo = DashBuddyApplication.dashZoneRepo
    private val zoneRepo = DashBuddyApplication.zoneRepo

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        Log.d(tag, "Evaluating state for event...")

        // The main setup happens in enterState.
        // This method transitions based on the screen after setup.
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
        Log.i(tag, "Entering state: Initializing new dash from persisted CurrentEntity.")

        try {
            // 1. Get the pre-dash info from the database.
            val currentInfo = currentRepo.getCurrentDashState()

            // The new failure condition: the zoneId was not persisted correctly.
            if (currentInfo?.zoneId == null) {
                Log.e(tag, "Cannot start dash, zoneId from Current table is null.")
                DashBuddyApplication.sendBubbleMessage("Error: Zone not set.\nCannot start dash.")
                return
            }

            val zoneId = currentInfo.zoneId
            val dashType = currentInfo.dashType

            // Get the zone name for the bubble message
            val zone = zoneRepo.getZoneById(zoneId)
            val zoneName = zone?.zoneName ?: "Unknown Zone"

            Log.d(
                tag,
                "Preparing to start dash in zone: '$zoneName' (ID: $zoneId), type: '$dashType'."
            )

            // 2. Insert new DashEntity using the data from the Current table
            val newDash = DashEntity(
                startTime = stateContext.timestamp,
                odometerStart = stateContext.odometerReading,
                dashType = dashType
            )
            val dashId = dashRepo.insertDash(newDash)
            if (dashId <= 0L) {
                Log.e(tag, "Failed to insert new dash. Received dashId: $dashId")
                DashBuddyApplication.sendBubbleMessage("Error: Could not start new dash session.")
                return
            }
            Log.i(tag, "New dash created with ID: $dashId for zoneId: $zoneId")

            // 3. Link the dash and zone (this logic is the same)
            dashZoneRepo.linkDashToZone(dashId, zoneId, true, stateContext.timestamp)
            Log.d(tag, "DashZone link created: $dashId -> $zoneId")

            // 4. Update Current table to make the dash fully active
            currentRepo.startNewActiveDash(
                dashId,
                zoneId,
                dashType,
                stateContext.timestamp,
            )
            Log.i(tag, "Current dash state updated.")

            // 5. Start location service to track mileage.
            val serviceIntent = Intent(DashBuddyApplication.context, LocationService::class.java)
            ContextCompat.startForegroundService(DashBuddyApplication.context, serviceIntent)

            // 6. Send a success bubble message
            DashBuddyApplication.sendBubbleMessage("Dashing in $zoneName\n(${dashType?.displayName})")

        } catch (e: Exception) {
            Log.e(tag, "!!! CRITICAL ERROR during dash starting process !!!", e)
            DashBuddyApplication.sendBubbleMessage("Error starting dash!\nCheck logs.")
        }
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "Exiting state to $nextState.")
    }
}
