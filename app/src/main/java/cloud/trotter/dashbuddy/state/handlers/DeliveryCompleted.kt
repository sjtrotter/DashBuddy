package cloud.trotter.dashbuddy.state.handlers

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.order.OrderEntity
import cloud.trotter.dashbuddy.data.order.OrderStatus
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
import cloud.trotter.dashbuddy.util.UtilityFunctions.stringsMatch
import kotlinx.coroutines.flow.first

class DeliveryCompleted : StateHandler {

    private val tag = "DeliveryCompletedHandler"

    // Repositories
    private val currentRepo = DashBuddyApplication.currentRepo
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
        Log.i(tag, "Commiting Pay Data to Database...")
        val parsedPay = screenInfo.parsedPay

        try {
            val current = currentRepo.getCurrentDashState()
            if (current?.dashId == null) {
                Log.w(tag, "Cannot record pay: No active Dash ID found.")
                return
            }

            val allActiveOrderIds =
                (current.activeOrderQueue + listOfNotNull(current.activeOrderId)).distinct()
            val activeOrders = allActiveOrderIds
                .mapNotNull { orderRepo.getOrderById(it) }
                .filter { it.status != OrderStatus.COMPLETED }

            if (activeOrders.isEmpty()) {
                Log.w(tag, "No active orders found to match pay against.")
                return
            }

            // Match Logic
            val firstStoreName = parsedPay.customerTips.firstOrNull()?.type
                ?: parsedPay.appPayComponents.firstOrNull()?.type

            var matchedOfferId: Long? = null
            var ordersForMatchedOffer: List<OrderEntity> = emptyList()

            if (firstStoreName != null) {
                val candidateOrders =
                    activeOrders.filter { stringsMatch(it.storeName, firstStoreName) }
                for (candidateOrder in candidateOrders) {
                    val ordersInDb = orderRepo.getOrdersForOffer(candidateOrder.offerId).first()
                    // Relaxed Match: If we found orders for this store, assume it's the one.
                    if (ordersInDb.isNotEmpty()) {
                        matchedOfferId = candidateOrder.offerId
                        ordersForMatchedOffer = ordersInDb
                        break
                    }
                }
            }

            if (matchedOfferId == null) {
                Log.e(tag, "Sanity Check FAILED: Could not match pay to an offer.")
                return
            }

            // Duplicate Check
            val existingAppPay = appPayRepo.getPayComponentsForOffer(matchedOfferId)
            if (existingAppPay.first().isNotEmpty()) {
                Log.i(tag, "Pay already exists for Offer #$matchedOfferId. Skipping insert.")
                return
            }

            // Insert Tips
            for (tipItem in parsedPay.customerTips) {
                val order = ordersForMatchedOffer.find { stringsMatch(it.storeName, tipItem.type) }
                if (order != null) {
                    tipRepo.insert(
                        TipEntity(
                            0,
                            order.id,
                            tipItem.amount,
                            TipType.INITIAL_TIP,
                            stateContext.timestamp
                        )
                    )
                }
            }

            // Insert App Pay
            for (appPayItem in parsedPay.appPayComponents) {
                val typeId = appPayRepo.upsertPayType(appPayItem.type)
                appPayRepo.insert(
                    AppPayEntity(
                        0,
                        matchedOfferId,
                        typeId,
                        appPayItem.amount,
                        stateContext.timestamp
                    )
                )
            }

            // Cleanup Status
            for (order in ordersForMatchedOffer) {
                currentRepo.removeOrderFromQueue(order.id)
                orderRepo.updateOrderStatus(order.id, OrderStatus.COMPLETED)
                orderRepo.updateCompletionTimestamp(order.id, stateContext.timestamp)
            }

            DashBuddyApplication.sendBubbleMessage("Delivery Recorded: $${parsedPay}")

        } catch (e: Exception) {
            Log.e(tag, "Error recording pay data", e)
        }
    }
}