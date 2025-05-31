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

    private val currentRepo = DashBuddyApplication.currentRepo
    private val dashRepo = DashBuddyApplication.dashRepo
    private val dashZoneRepo = DashBuddyApplication.dashZoneRepo
    private val zoneRepo = DashBuddyApplication.zoneRepo

    override fun processEvent(context: StateContext, currentState: AppState): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")
        // process event here

        // the whole point of this handler is to initialize the dash in enterState.
        // after, here, we just check the screen and transition to the next state.
        if (context.dasherScreen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER) return AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
        if (context.dasherScreen == Screen.ON_DASH_ALONG_THE_WAY) return AppState.SESSION_ACTIVE_DASHING_ALONG_THE_WAY

        return currentState
    }

    override fun enterState(
        context: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d("${this::class.simpleName} State", "Entering state...")
        // initialize components here

        // The previous state incoming should have checked if a dash has already started.
        // So, we need to initialize the dash here.
        // TODO: update Current table.
        val zoneName = Manager.consumePreDashZone()
        if (zoneName != null) {
            Manager.getScope().launch {
                // first insert/select zone
                val zoneId = zoneRepo.getOrInsertZone(zoneName)
                Log.d(
                    "${this::class.simpleName} State",
                    "Created/Retrieved Zone ID: $zoneId ($zoneName)"
                )

                // then insert new dash
                val dashId =
                    dashRepo.insertDash(DashEntity(zoneId = zoneId, startTime = context.timestamp))
                Log.d("${this::class.simpleName} State", "Created Dash ID: $dashId")

                // then insert link in DashZone table
                dashZoneRepo.linkDashToZone(dashId, zoneId, true, context.timestamp)
                Log.d(
                    "${this::class.simpleName} State",
                    "Created DashZone Link: $dashId -> $zoneId"
                )

                // then, update Current table.
                currentRepo.startNewActiveDash(dashId, zoneId, null)
            }
        } else {
            Log.e(
                "${this::class.simpleName} State",
                "No pre-dash zone found -- cannot initialize dash."
            )
            return
        }

        DashBuddyApplication.sendBubbleMessage("${currentState.displayName} State\n${context.dasherScreen?.screenName} Screen")

        Log.d("${this::class.simpleName} State", "We should have initialized the dash here.")
    }

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}