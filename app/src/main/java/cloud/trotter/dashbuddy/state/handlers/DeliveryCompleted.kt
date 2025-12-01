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
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.util.AccNodeUtils

class DeliveryCompleted : StateHandler {

    private val tag = "DeliveryCompletedHandler"

    // Repositories
//    private val currentRepo = DashBuddyApplication.currentRepo
    private val orderRepo = DashBuddyApplication.orderRepo
    private val tipRepo = DashBuddyApplication.tipRepo
    private val appPayRepo = DashBuddyApplication.appPayRepo

    /**
     * PASSIVE LOOP:
     * We do NOT click here. We only check if the data has appeared.
     */
    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        val screenInfo = stateContext.screenInfo
        val screen = screenInfo?.screen

        // 1. EXIT STRATEGIES (Higher Priority)
        // If the screen changed, we must leave.
        when {
            screen == Screen.OFFER_POPUP -> return AppState.DASH_ACTIVE_OFFER_PRESENTED

            screen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER ||
                    screen == Screen.ON_DASH_ALONG_THE_WAY ||
                    screen == Screen.MAIN_MAP_IDLE -> return AppState.DASH_ACTIVE_AWAITING_OFFER

            screen?.isPickup == true -> return AppState.DASH_ACTIVE_ON_PICKUP
            screen?.isDelivery == true -> return AppState.DASH_ACTIVE_ON_DELIVERY
        }

        // 2. CHECK FOR PAY DATA
        // Only proceed if we are actually on the Delivery Completed screen.
        if (screenInfo is ScreenInfo.DeliveryCompleted) {
            val hasData = screenInfo.parsedPay.appPayComponents.isNotEmpty() ||
                    screenInfo.parsedPay.customerTips.isNotEmpty()

            if (hasData) {
                Log.i(tag, "Pay data detected. Transitioning to POST_DELIVERY to record.")
                return AppState.DASH_POST_DELIVERY
            }
        }

        // 3. NO DATA YET? JUST WAIT.
        // We performed the click in 'enterState'. Now we wait for the UI to update.
        // If the click failed, we rely on the user to manually click it, which will
        // trigger a new event, landing us back here to check 'hasData' again.
        return currentState
    }

    /**
     * THE ACTION:
     * Run exactly ONCE when we first enter the screen.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "Entering state: DeliveryCompleted")

        val screenInfo = stateContext.screenInfo as? ScreenInfo.DeliveryCompleted
        val hasData = screenInfo?.parsedPay?.let {
            it.appPayComponents.isNotEmpty() || it.customerTips.isNotEmpty()
        } ?: false

        if (hasData) {
            Log.i(
                tag,
                "Data already visible on entry. No click needed. Waiting for processEvent..."
            )
            return
        }

        Log.i(tag, "Data hidden on entry. Attempting ONE click on 'This offer'...")
        val expandButton = stateContext.rootUiNode?.findNode {
            it.text?.startsWith("This offer") == true
        }

        if (expandButton != null) {
            val success = AccNodeUtils.clickNode(expandButton.originalNode)
            if (success) Log.i(tag, "Click sent.")
        }
    }

    /**
     * THE SIDE EFFECT:
     * Record data ONLY when we successfully exit to the POST_DELIVERY state.
     */
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
            } else {
                Log.e(
                    tag,
                    "Transitioned to POST_DELIVERY but screen info was not DeliveryCompleted! Data lost."
                )
            }
        } else {
            Log.i(tag, "Exiting DeliveryCompleted without recording (Next State: $nextState).")
        }
    }

    // --- HELPER: DATABASE RECORDING ---

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