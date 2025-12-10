package cloud.trotter.dashbuddy.state.handlers

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.event.PickupEventEntity
import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.state.StateHandler

class OnPickup : StateHandler {

    private val tag = "OnPickupHandler"
    private val currentRepo = DashBuddyApplication.currentRepo
    private val pickupEventRepo = DashBuddyApplication.pickupEventRepo

    // Deduplication State
    private var lastLoggedStoreName: String? = null
    private var lastLoggedStoreAddress: String? = null
    private var lastLoggedStatus: PickupStatus? = null

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "--- Entering Pickup Phase ---")
        lastLoggedStoreName = null
        lastLoggedStoreAddress = null
        lastLoggedStatus = null

        capturePickupEvent(stateContext)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        val screen = stateContext.screenInfo?.screen ?: return currentState

        return when {
            // Transitions
            screen == Screen.PICKUP_DETAILS_PICKED_UP -> AppState.DASH_ACTIVE_PICKED_UP
            screen.isDelivery -> AppState.DASH_ACTIVE_ON_DELIVERY
            screen == Screen.OFFER_POPUP -> AppState.DASH_ACTIVE_OFFER_PRESENTED
            screen == Screen.TIMELINE_VIEW -> AppState.DASH_ACTIVE_ON_TIMELINE
            screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_IDLE_OFFLINE

            // Capture Data
            else -> {
                capturePickupEvent(stateContext)
                currentState
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun capturePickupEvent(context: StateContext) {
        // 1. Cast to the specific PickupDetails
        val details = context.screenInfo as? ScreenInfo.PickupDetails ?: return

        val storeName = details.storeName ?: lastLoggedStoreName
        val storeAddress = details.storeAddress ?: lastLoggedStoreAddress
        val currentStatus = details.status

        // 2. Validation
        if (storeName.isNullOrBlank()) return
        if (currentStatus == PickupStatus.UNKNOWN) return

        // 3. Deduplication
        if (storeName == lastLoggedStoreName && currentStatus == lastLoggedStatus) {
            return
        }

        Log.i(tag, "Pickup Event: $storeName is now $currentStatus. Logging.")
        DashBuddyApplication.sendBubbleMessage("Pickup: $storeName ($currentStatus)")

        val currentDash = currentRepo.getCurrentDashState()

        val event = PickupEventEntity(
            dashId = currentDash?.dashId,
            rawStoreName = storeName,
            rawAddress = storeAddress,
            status = currentStatus, // Enum stored directly
            odometerReading = context.odometerReading
        )

        pickupEventRepo.insert(event)

        // 4. Update Memory
        lastLoggedStoreName = storeName
        lastLoggedStatus = currentStatus
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "--- Exiting Pickup Phase ---")
    }
}