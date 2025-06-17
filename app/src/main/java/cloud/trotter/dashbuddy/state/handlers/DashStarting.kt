package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.state.Manager
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen
import kotlinx.coroutines.launch

class DashStarting : StateHandler {

    private val tag = this::class.simpleName ?: "DashStarting"

    private val currentRepo = DashBuddyApplication.currentRepo
    private val dashRepo = DashBuddyApplication.dashRepo
    private val dashZoneRepo = DashBuddyApplication.dashZoneRepo
    private val zoneRepo = DashBuddyApplication.zoneRepo

    override fun processEvent(context: StateContext, currentState: AppState): AppState {
        Log.d(tag, "Evaluating state for event...")

        // The main setup happens in enterState.
        // This method transitions based on the screen after setup.
        return when (context.dasherScreen) {
            Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> {
                Log.i(
                    tag,
                    "Screen is ON_DASH_MAP_WAITING_FOR_OFFER, transitioning to SESSION_ACTIVE_WAITING_FOR_OFFER."
                )
                AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
            }

            Screen.ON_DASH_ALONG_THE_WAY -> {
                Log.i(
                    tag,
                    "Screen is ON_DASH_ALONG_THE_WAY, transitioning to SESSION_ACTIVE_DASHING_ALONG_THE_WAY."
                )
                AppState.SESSION_ACTIVE_DASHING_ALONG_THE_WAY
            }

            else -> currentState
        }
    }

    override fun enterState(
        context: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "Entering state: Initializing new dash.")

        val zoneName = Manager.consumePreDashZone()
        val dashType = Manager.consumePreDashType()

        if (zoneName.isNullOrBlank()) {
            Log.e(tag, "No pre-dash zone name found or it's blank. Cannot initialize dash.")
            DashBuddyApplication.sendBubbleMessage("Error: Zone not set.\nCannot start dash.")
            // Optionally, transition to an error state or back to an idle state via Manager if possible,
            // or rely on processEvent to eventually move out if screen changes.
            return
        }

        Log.d(
            tag,
            "Preparing to start dash in zone: '$zoneName', type: '$dashType'. Timestamp: ${context.timestamp}"
        )
        DashBuddyApplication.sendBubbleMessage("Starting Dash: $zoneName\n($dashType)")

        Manager.enqueueDbWork {
            try {
                // 1. Get or Insert Zone to get zoneId
                Log.d(tag, "Getting/inserting zone: $zoneName")
                // Assuming getOrInsertZone returns the ID. If it can fail or return an invalid ID, add checks.
                val zoneId = zoneRepo.getOrInsertZone(zoneName)
                if (zoneId <= 0L) { // Basic check for a valid ID
                    Log.e(
                        tag,
                        "Failed to get or insert a valid zoneId for zoneName: $zoneName. Received zoneId: $zoneId"
                    )
                    DashBuddyApplication.sendBubbleMessage("Error: Could not setup zone '$zoneName'.")
                    return@enqueueDbWork
                }
                Log.i(tag, "Zone ID for '$zoneName': $zoneId")

                // 2. Insert new DashEntity
                val newDash = DashEntity(
                    zoneId = zoneId,
                    startTime = context.timestamp, // Use event timestamp as start time
                    earningMode = dashType
                )
                Log.d(tag, "Inserting new dash: $newDash")
                val dashId = dashRepo.insertDash(newDash)
                if (dashId <= 0L) {
                    Log.e(tag, "Failed to insert new dash. Received dashId: $dashId")
                    DashBuddyApplication.sendBubbleMessage("Error: Could not start new dash session.")
                    return@enqueueDbWork
                }
                Log.i(tag, "New dash created with ID: $dashId for zoneId: $zoneId")

                // 3. Insert link in DashZone table (if you have such a table/concept)
                // This step depends on your specific DashZoneEntity structure and DAO.
                // For example, it might record that this dash is active in this zone.
                dashZoneRepo.linkDashToZone(dashId, zoneId, true, context.timestamp)
                Log.d(tag, "DashZone link created: $dashId -> $zoneId")

                // 4. Update Current table to reflect the new active dash
                Log.d(
                    tag,
                    "Updating CurrentEntity with new active dash: dashId=$dashId, zoneId=$zoneId"
                )
                currentRepo.startNewActiveDash(
                    dashId,
                    zoneId,
                    dashType,
                    context.timestamp,
                )
                Log.i(tag, "Current dash state updated. Dash successfully started.")

                // Optionally send a success confirmation
                DashBuddyApplication.sendBubbleMessage("Dash Started!\nZone: $zoneName")

            } catch (e: Exception) {
                Log.e(tag, "!!! CRITICAL ERROR during dash starting process !!!", e)
                DashBuddyApplication.sendBubbleMessage("Error starting dash!\nCheck logs.")
                // Attempt to clean up if possible, though it's hard to know the exact state.
                // For instance, if dash was inserted but CurrentEntity update failed.
                // This might require more sophisticated rollback or cleanup logic.
            }
        }
        Log.d(tag, "Dash initialization added to queue.")
    }

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.i(tag, "Exiting state to $nextState.")
        // Cleanup specific to DashStarting, if any (usually not much for a starting state)
    }
}
