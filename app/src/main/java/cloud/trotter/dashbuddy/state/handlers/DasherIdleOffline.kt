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
        // 1. Safety Checks & Transitions
        val screen = stateContext.screenInfo?.screen

        if (screen == Screen.DASH_CONTROL) {
            return AppState.DASH_ACTIVE_ON_CONTROL
        }

        if (screen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER ||
            screen == Screen.ON_DASH_ALONG_THE_WAY
        ) {
            return AppState.DASH_STARTING
        }

        // 2. Data Extraction
        // We reuse ScreenInfo.IdleMap for both the Main Map and the Set End Time screen
        if (stateContext.screenInfo is ScreenInfo.IdleMap) {
            updatePreDashData(
                rawZoneName = stateContext.screenInfo.zoneName,
                rawDashType = stateContext.screenInfo.dashType
            )
        }

        return currentState
    }

    /**
     * Helper to centralize the DB update logic.
     * Handles zone insertion and prevents unnecessary DB writes.
     */
    private suspend fun updatePreDashData(rawZoneName: String?, rawDashType: DashType?) {
        try {
            // Get current state or create default
            val currentData = currentRepo.getCurrentDashState() ?: CurrentEntity()

            // 1. Resolve Zone ID
            // If rawZoneName is null, keep the existing ID.
            // If it's new, get/insert it.
            val resolvedZoneId = if (rawZoneName != null) {
                zoneRepo.getOrInsertZone(rawZoneName)
            } else {
                currentData.zoneId
            }

            // 2. Resolve Dash Type
            // If the screen didn't provide a type (e.g. Set End Time screen),
            // keep the existing one. Default to PER_OFFER only if absolutely nothing exists.
            val resolvedDashType = rawDashType ?: currentData.dashType ?: DashType.PER_OFFER

            // 3. Update only if changed
            val needsUpdate = (currentData.zoneId != resolvedZoneId) ||
                    (currentData.dashType != resolvedDashType)

            if (needsUpdate) {
                currentRepo.updatePreDashInfo(resolvedZoneId, resolvedDashType)
                Log.i(
                    "DasherIdleOffline",
                    "Updated Pre-Dash: Zone=$rawZoneName, Type=$resolvedDashType"
                )
            }

        } catch (e: Exception) {
            Log.e("DasherIdleOffline", "Error updating pre-dash data", e)
        }
    }

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d("${this::class.simpleName}", "Entering Idle Offline State")
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        // No-op
    }
}