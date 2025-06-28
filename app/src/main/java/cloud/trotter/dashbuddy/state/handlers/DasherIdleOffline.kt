package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.data.dash.DashType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen

class DasherIdleOffline : StateHandler {

    private val currentRepo = DashBuddyApplication.currentRepo
    private val zoneRepo = DashBuddyApplication.zoneRepo

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")

        // Seems that the on dash map waiting for offer might recognize the main map idle screen
        // when backing out to dash control. putting this in to catch and re-orient.
        if (stateContext.screenInfo?.screen == Screen.DASH_CONTROL) return AppState.DASH_ACTIVE_ON_CONTROL

        // if a dash is not started:
        if (stateContext.screenInfo?.screen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER ||
            stateContext.screenInfo?.screen == Screen.ON_DASH_ALONG_THE_WAY
        )
            return AppState.DASH_STARTING

        if (stateContext.screenInfo is ScreenInfo.IdleMap) {
            try {
                // Get the current state from the DB, or create a default empty one
                val currentData = currentRepo.getCurrentDashState() ?: CurrentEntity()

                // Get the new data from the screen parser
                val newZoneId =
                    stateContext.screenInfo.zoneName?.let { zoneRepo.getOrInsertZone(it) }

                val newDashType = stateContext.screenInfo.dashType ?: DashType.PER_OFFER.also {
                    Log.d("DasherIdleOffline", "DashType is null, defaulting to PER_OFFER")
                }

                // Check if an update is actually needed
                val needsUpdate =
                    (currentData.zoneId != newZoneId) || (currentData.dashType != newDashType)

                if (needsUpdate) {
                    // If and only if something changed, perform a SINGLE update
                    // with both new values.
                    currentRepo.updatePreDashInfo(newZoneId, newDashType)
                    Log.i("DasherIdleOffline", "Pre-dash info changed, updating Current table.")
                }

            } catch (e: Exception) {
                Log.e("DasherIdleOffline", "!!! Error updating pre-dash zone/type data. !!!", e)
            }
        } else {
            Log.d("DasherIdleOffline", "ScreenInfo is not IdleMap: ${stateContext.screenInfo}")
        }

        return currentState
    }

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d("${this::class.simpleName} State", "Entering state...")
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}