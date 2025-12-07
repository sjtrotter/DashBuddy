package cloud.trotter.dashbuddy.state.handlers

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.event.PickupEventEntity
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.state.StateHandler

class OnPickup : StateHandler {

    private val tag = "OnPickupHandler"
    private val currentRepo = DashBuddyApplication.currentRepo

    // You will likely add this to your Application class just like currentRepo
    private val pickupEventRepo = DashBuddyApplication.pickupEventRepo

    // Deduplication State: We now track BOTH to allow status transitions
    private var lastLoggedStoreName: String? = null
    private var lastLoggedStatus: String? = null

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "--- Entering Pickup Phase ---")
        // Reset memory on entry
        lastLoggedStoreName = null
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
            // Transitions out of Pickup
            screen == Screen.PICKUP_DETAILS_PICKED_UP -> AppState.DASH_ACTIVE_PICKED_UP
            screen.isDelivery -> AppState.DASH_ACTIVE_ON_DELIVERY
            screen == Screen.OFFER_POPUP -> AppState.DASH_ACTIVE_OFFER_PRESENTED
            screen == Screen.TIMELINE_VIEW -> AppState.DASH_ACTIVE_ON_TIMELINE
            screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_IDLE_OFFLINE

            // Still in Pickup? Capture data.
            else -> {
                capturePickupEvent(stateContext)
                currentState
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun capturePickupEvent(context: StateContext) {
        val details = context.screenInfo as? ScreenInfo.OrderDetails ?: return
        val storeName = details.storeName

        // Map the screen to a readable status string
        val currentStatus = when (context.screenInfo.screen) {
            Screen.NAVIGATION_VIEW_TO_PICK_UP -> "NAVIGATING"
            Screen.PICKUP_DETAILS_PRE_ARRIVAL -> "NAVIGATING" // Or "PRE_ARRIVAL"
            Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP -> "SHOPPING"
            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE,
            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_MULTI -> "ARRIVED"

            else -> "UNKNOWN" // Fallback
        }

        // VALIDATION: We need a store name
        if (storeName.isNullOrBlank()) return

        // DEDUPLICATION: Only skip if BOTH store and status are identical to the last log
        if (storeName == lastLoggedStoreName && currentStatus == lastLoggedStatus) {
            return
        }

        Log.i(tag, "Pickup Update: $storeName is now $currentStatus. Logging Event.")
        DashBuddyApplication.sendBubbleMessage("On Pickup from $storeName - $currentStatus")

        val currentDash = currentRepo.getCurrentDashState()

        val event = PickupEventEntity(
            dashId = currentDash?.dashId,
            rawStoreName = storeName,
            rawAddress = details.storeAddress,
            status = currentStatus,
            odometerReading = context.odometerReading
        )

        pickupEventRepo.insert(event)

        // Update memory
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