package cloud.trotter.dashbuddy.state.handlers

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.order.OrderEntity
import cloud.trotter.dashbuddy.data.order.OrderStatus
import cloud.trotter.dashbuddy.data.order.OrderType
import cloud.trotter.dashbuddy.data.pay.AppPayEntity
import cloud.trotter.dashbuddy.data.pay.TipEntity
import cloud.trotter.dashbuddy.data.pay.TipType
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.util.AccNodeUtils
import kotlinx.coroutines.delay

class DeliveryCompleted : StateHandler {

    private val tag = "DeliveryCompletedHandler"
    private val orderRepo = DashBuddyApplication.orderRepo
    private val tipRepo = DashBuddyApplication.tipRepo
    private val appPayRepo = DashBuddyApplication.appPayRepo

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        val screen = stateContext.screenInfo?.screen
        Log.i(tag, "Entering DeliveryCompleted. Initial Screen: $screen")

        // STRATEGY: Check -> Wait -> Act
        if (screen == Screen.DELIVERY_SUMMARY_COLLAPSED) {
            Log.i(tag, "Screen is Collapsed. Waiting for UI to settle...")

            // 1. Wait for animation/load (Your idea: Delay before clicking)
            delay(500)

            // 2. Perform the Click
            // Target the specific container you identified: id=expandable_view
            val root = stateContext.rootUiNode
            val expandButton = root?.findNode {
                it.viewIdResourceName?.endsWith("expandable_view") == true
            } ?: root?.findNode {
                it.text?.startsWith("This offer") == true
            }

            if (expandButton != null) {
                Log.i(
                    tag,
                    "Attempting to expand details via '${expandButton.viewIdResourceName ?: "text"}'"
                )
                AccNodeUtils.clickNode(expandButton.originalNode)
            } else {
                Log.w(tag, "Could not find expand button!")
            }
        }
    }

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        val screenInfo = stateContext.screenInfo
        val screen = screenInfo?.screen

        // 1. EXIT STRATEGIES (User navigated away)
        if (screen == Screen.MAIN_MAP_IDLE || screen == Screen.OFFER_POPUP) {
            return AppState.DASH_ACTIVE_AWAITING_OFFER
        }

        // 2. SUCCESS STRATEGY (Data Detected)
        // If the Matcher successfully parsed the expanded data, we move to record it.
        if (screenInfo is ScreenInfo.DeliveryCompleted) {
            val hasData = screenInfo.parsedPay.appPayComponents.isNotEmpty() ||
                    screenInfo.parsedPay.customerTips.isNotEmpty()

            if (hasData) {
                Log.i(tag, "Expanded Data Detected! Transitioning to Record.")
                return AppState.DASH_POST_DELIVERY
            }
        }

        // 3. WAITING STRATEGY
        // If we remain in COLLAPSED, we just wait.
        // If the click failed, the user will likely tap it themselves, triggering a new event.
        return currentState
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        if (nextState == AppState.DASH_POST_DELIVERY) {
            val screenInfo = stateContext.screenInfo
            if (screenInfo is ScreenInfo.DeliveryCompleted) {
                recordPayData(stateContext, screenInfo)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun recordPayData(
        stateContext: StateContext,
        screenInfo: ScreenInfo.DeliveryCompleted
    ) {
        Log.i(tag, "Commiting Pay Data to Database (Paradigm: Always Record)...")
        val parsedPay = screenInfo.parsedPay

        try {
            // 1. Create Orders & Tips
            // We create a new "Completed" order for every store found in the tips section.
            // This ensures we always verify the order exists, even if orphaned.
            for (tipItem in parsedPay.customerTips) {
                val newOrder = OrderEntity(
                    offerId = null, // Orphaned: Matching to Offer happens later
                    orderIndex = 0,
                    storeName = tipItem.type,
                    orderType = OrderType.PICKUP, // Default safe type
                    status = OrderStatus.COMPLETED,
                    odometerReading = stateContext.odometerReading,
                    completionTimestamp = stateContext.timestamp
                )

                // Insert the order to get its new ID
                val newOrderId = orderRepo.insertOrder(newOrder)
                Log.i(tag, "Created new Order #$newOrderId for store '${tipItem.type}'")

                // Link the tip to this new order
                val newTip = TipEntity(
                    orderId = newOrderId,
                    amount = tipItem.amount,
                    type = TipType.INITIAL_TIP,
                    timestamp = stateContext.timestamp
                )
                tipRepo.insert(newTip)
                Log.i(tag, " -> Recorded tip: $${tipItem.amount}")
                DashBuddyApplication.sendBubbleMessage("Order Recorded: ${tipItem.type} - $${tipItem.amount}")
            }

            // 2. Create App Pay
            // We create app pay entries orphaned from any offer.
            for (appPayItem in parsedPay.appPayComponents) {
                val typeId = appPayRepo.upsertPayType(appPayItem.type)
                val newAppPay = AppPayEntity(
                    offerId = null, // Orphaned: Matching to Offer happens later
                    payTypeId = typeId,
                    amount = appPayItem.amount,
                    timestamp = stateContext.timestamp
                )
                appPayRepo.insert(newAppPay)
                Log.i(tag, "Recorded App Pay: ${appPayItem.type} -> $${appPayItem.amount}")
            }

            DashBuddyApplication.sendBubbleMessage("Delivery Recorded: $${parsedPay}")

        } catch (e: Exception) {
            Log.e(tag, "Error recording pay data", e)
        }
    }
}