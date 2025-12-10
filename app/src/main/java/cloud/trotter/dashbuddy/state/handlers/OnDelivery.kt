package cloud.trotter.dashbuddy.state.handlers

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.event.DropoffEventEntity
import cloud.trotter.dashbuddy.data.event.status.DropoffStatus
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.state.StateHandler

class OnDelivery : StateHandler {

    private val tag = "OnDeliveryHandler"
    private val currentRepo = DashBuddyApplication.currentRepo
    private val dropoffEventRepo = DashBuddyApplication.dropoffEventRepo

    // Deduplication State
    // We track the HASH since we don't store the name
    private var lastLoggedCustomerHash: String? = null
    private var lastLoggedCustomerAddress: String? = null
    private var lastLoggedStatus: DropoffStatus? = null

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "--- Entering Delivery Phase ---")
        lastLoggedCustomerHash = null
        lastLoggedCustomerAddress = null
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
            // Transitions
            screen == Screen.DELIVERY_SUMMARY_COLLAPSED -> AppState.DASH_ACTIVE_DELIVERY_COMPLETED
            screen.isPickup -> AppState.DASH_ACTIVE_ON_PICKUP // Stacked orders
            screen == Screen.OFFER_POPUP -> AppState.DASH_ACTIVE_OFFER_PRESENTED
            screen == Screen.TIMELINE_VIEW -> AppState.DASH_ACTIVE_ON_TIMELINE
            screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_IDLE_OFFLINE

            else -> {
                captureDropoffEvent(stateContext)
                currentState
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun captureDropoffEvent(context: StateContext) {
        // 1. Cast to DropoffDetails
        val details = context.screenInfo as? ScreenInfo.DropoffDetails ?: return

        // We use the HASH for identification/deduplication
        val customerHash = details.customerNameHash ?: lastLoggedCustomerHash
        val customerAddress = details.addressHash ?: lastLoggedCustomerAddress
        val currentStatus = details.status

        if (customerHash.isNullOrBlank()) return
        if (currentStatus == DropoffStatus.UNKNOWN) return

        // 2. Deduplication
        if (customerHash == lastLoggedCustomerHash && currentStatus == lastLoggedStatus) {
            return
        }

        Log.i(tag, "Dropoff Event: CustomerHash=$customerHash is now $currentStatus")
        DashBuddyApplication.sendBubbleMessage("Delivery Update: $currentStatus")

        val currentDash = currentRepo.getCurrentDashState()

        val event = DropoffEventEntity(
            dashId = currentDash?.dashId,
            customerNameHash = customerHash,
            addressHash = customerAddress,
            status = currentStatus,
            odometerReading = context.odometerReading
        )

        dropoffEventRepo.insert(event)

        // 3. Update Memory
        lastLoggedCustomerHash = customerHash
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