package cloud.trotter.dashbuddy.state.handlers

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.event.DropoffEventEntity
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.state.StateHandler

/**
 * Manages the delivery phase events.
 * Logs navigation and arrival status to the database for timeline reconstruction.
 */
class OnDelivery : StateHandler {

    private val tag = "OnDeliveryHandler"

    // Dependencies
    private val currentRepo = DashBuddyApplication.currentRepo

    // Assumes you've added this to DashBuddyApplication
    private val dropoffEventRepo = DashBuddyApplication.dropoffEventRepo

    // Deduplication State
    private var lastLoggedCustomer: String? = null
    private var lastLoggedStatus: String? = null

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "--- Entering Delivery Phase ---")
        // Reset memory
        lastLoggedCustomer = null
        lastLoggedStatus = null

        captureDropoffEvent(stateContext)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        val screen = stateContext.screenInfo?.screen ?: return currentState

        return when {
            // --- Transitions OUT of Delivery ---

            // Delivery Completed (The "Receipt")
            screen == Screen.DELIVERY_COMPLETED_DIALOG -> AppState.DASH_ACTIVE_DELIVERY_COMPLETED

            // Moved back to Pickup (Stacked orders?)
            screen.isPickup -> AppState.DASH_ACTIVE_ON_PICKUP

            // Interruptions
            screen == Screen.OFFER_POPUP -> AppState.DASH_ACTIVE_OFFER_PRESENTED
            screen == Screen.TIMELINE_VIEW -> AppState.DASH_ACTIVE_ON_TIMELINE
            screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_IDLE_OFFLINE

            // --- Stay IN Delivery ---
            else -> {
                captureDropoffEvent(stateContext)
                currentState
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun captureDropoffEvent(context: StateContext) {
        val details = context.screenInfo as? ScreenInfo.OrderDetails ?: return

        // Note: Check your ScreenInfo definition.
        // We use storeName/customerName depending on how you map the generic text.
        // Assuming 'storeName' field is reused for the primary title (Customer Name)
        val customerName = details.storeName

        if (customerName.isNullOrBlank()) return

        // Map Screen to Status
        val currentStatus = when (context.screenInfo.screen) {
            Screen.NAVIGATION_VIEW_TO_DROP_OFF -> "NAVIGATING"
            Screen.DROPOFF_DETAILS_PRE_ARRIVAL -> "NAVIGATING" // or "PRE_ARRIVAL"
            // If you add POST_ARRIVAL screens later, map them to "ARRIVED"
            else -> "UNKNOWN"
        }

        // Deduplication
        if (customerName == lastLoggedCustomer && currentStatus == lastLoggedStatus) {
            return
        }

        Log.i(tag, "Logging Dropoff Event: $customerName is now $currentStatus")
        DashBuddyApplication.sendBubbleMessage("On Delivery for $customerName - $currentStatus")

        val currentDash = currentRepo.getCurrentDashState()

        val event = DropoffEventEntity(
            dashId = currentDash?.dashId,
            rawCustomerName = customerName,
            rawAddress = details.storeAddress, // Assuming this maps to subtitle/address
            status = currentStatus,
            odometerReading = context.odometerReading
        )

        dropoffEventRepo.insert(event)

        // Update Memory
        lastLoggedCustomer = customerName
        lastLoggedStatus = currentStatus
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "--- Exiting Delivery Phase ---")
    }
}