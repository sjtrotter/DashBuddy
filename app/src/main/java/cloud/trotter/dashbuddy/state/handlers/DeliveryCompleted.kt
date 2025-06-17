package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.order.OrderEntity
import cloud.trotter.dashbuddy.data.order.OrderStatus
import cloud.trotter.dashbuddy.data.pay.AppPayEntity
import cloud.trotter.dashbuddy.data.pay.AppPayType
import cloud.trotter.dashbuddy.data.pay.PayParser
import cloud.trotter.dashbuddy.data.pay.TipEntity
import cloud.trotter.dashbuddy.data.pay.TipType
import cloud.trotter.dashbuddy.state.Manager
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen
import cloud.trotter.dashbuddy.util.NodeActionUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DeliveryCompleted : StateHandler {

    private val tag = this::class.simpleName ?: "DeliveryCompletedHandler"
    private var wasClickAttempted = false
    private var wasPayRecorded = false
    private val currentRepo = DashBuddyApplication.currentRepo
    private val orderRepo = DashBuddyApplication.orderRepo
    private val tipRepo = DashBuddyApplication.tipRepo
    private val appPayRepo = DashBuddyApplication.appPayRepo

    override fun processEvent(context: StateContext, currentState: AppState): AppState {
        Log.d(tag, "Evaluating event. Current Screen: ${context.dasherScreen}")

        if (!wasPayRecorded && context.screenTexts.any {
                it.contains("Customer Tips", ignoreCase = true)
            }) {
            Log.i(tag, "Pay breakdown detected. Attempting to parse pays.")
            val parsedPay = PayParser.parsePay(context.screenTexts)
            Manager.enqueueDbWork {
                try {
                    val completedCountOnScreen = parsedPay.customerTips.size
                    if (completedCountOnScreen == 0) {
                        Log.w(
                            tag,
                            "Pay breakdown was detected, but no customer tips were parsed. Aborting."
                        )
                        return@enqueueDbWork
                    }
                    Log.d(tag, "Detected $completedCountOnScreen completed deliver(ies) on screen.")

                    val current = currentRepo.getCurrentDashState() ?: return@enqueueDbWork
                    val activeOrders = current.activeOrderQueue
                        .mapNotNull { orderRepo.getOrderById(it) }
                        .filter { it.status != OrderStatus.COMPLETED }

                    if (activeOrders.isEmpty()) {
                        Log.w(tag, "Pay breakdown seen, but no non-completed orders in queue.")
                        return@enqueueDbWork
                    }

                    // --- NEW: Sanity Check to Find the Correct Offer ---
                    var matchedOfferId: Long? = null
                    var ordersForMatchedOffer: List<OrderEntity> = emptyList()

                    // Find a candidate order from the first parsed tip to identify a potential offer
                    val firstTipStoreName = parsedPay.customerTips.first().type
                    val candidateOrders =
                        activeOrders.filter { namesMatch(it.storeName, firstTipStoreName) }

                    for (candidateOrder in candidateOrders) {
                        val ordersInDbForOffer =
                            orderRepo.getOrdersForOffer(candidateOrder.offerId).first()

                        // THE SANITY CHECK: Does the number of orders in the database for this offer
                        // match the number of completed deliveries we see on screen?
                        if (ordersInDbForOffer.size == completedCountOnScreen) {
                            // High-confidence match found!
                            matchedOfferId = candidateOrder.offerId
                            ordersForMatchedOffer = ordersInDbForOffer
                            Log.i(
                                tag,
                                "Sanity Check Passed: Matched Offer #$matchedOfferId which has the expected $completedCountOnScreen order(s)."
                            )
                            break
                        }
                    }

                    if (matchedOfferId == null) {
                        Log.e(
                            tag,
                            "Sanity Check FAILED: Could not find an active offer that matched the shape of the payout screen ($completedCountOnScreen deliveries). Aborting pay processing."
                        )
                        return@enqueueDbWork
                    }
                    // --- End of Sanity Check ---

                    // if there is already AppPay for this offer, we can return
                    // - we've already processed this pay screen.
                    val existingAppPay = appPayRepo.getPayComponentsForOffer(matchedOfferId)
                    if (existingAppPay.first().isNotEmpty()) {
                        Log.i(
                            tag,
                            "Existing AppPay found for offer $matchedOfferId. Aborting pay processing."
                        )
                        return@enqueueDbWork
                    }


                    val completedOrderIds = mutableListOf<Long>()

                    // Now that we've confirmed the offer, process each tip against its orders.
                    for (tipItem in parsedPay.customerTips) {
                        val orderToComplete =
                            ordersForMatchedOffer.find { namesMatch(it.storeName, tipItem.type) }

                        if (orderToComplete != null) {
                            Log.i(
                                tag,
                                "Processing completed order #${orderToComplete.id} ('${orderToComplete.storeName}')"
                            )
                            // 1. Update status to COMPLETED
                            orderRepo.updateOrderStatus(orderToComplete.id, OrderStatus.COMPLETED)

                            // 2. Save the tip, linking to this order
                            val tipEntity = TipEntity(
                                orderId = orderToComplete.id,
                                amount = tipItem.amount,
                                type = TipType.IN_APP_INITIAL,
                                timestamp = context.timestamp
                            )
                            tipRepo.insert(tipEntity)

                            completedOrderIds.add(orderToComplete.id)
                        }
                    }

                    // Process App-level pay and link it to the confirmed Offer
                    for (appPayItem in parsedPay.appPayComponents) {
                        val payTypeId = appPayRepo.upsertPayType(appPayItem.type)
                        val appPayEntity = AppPayEntity(
                            offerId = matchedOfferId,
                            payTypeId = payTypeId,
                            amount = appPayItem.amount,
                            timestamp = context.timestamp
                        )
                        appPayRepo.insert(appPayEntity)
                    }

                    // Remove all newly completed orders from the active queue
                    if (completedOrderIds.isNotEmpty()) {
                        for (orderId in completedOrderIds) {
                            currentRepo.removeOrderFromQueue(orderId)
                        }
                        Log.i(tag, "Removed completed order IDs $completedOrderIds from the queue.")
                    }
                    wasPayRecorded = true

                } catch (e: Exception) {
                    Log.e(tag, "!!! CRITICAL error while processing pay breakdown !!!", e)
                }
            }

        }

        return when (context.dasherScreen) {
            Screen.OFFER_POPUP -> AppState.SESSION_ACTIVE_OFFER_PRESENTED
            Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
            Screen.ON_DASH_ALONG_THE_WAY -> AppState.SESSION_ACTIVE_DASHING_ALONG_THE_WAY

            Screen.DELIVERY_COMPLETED_DIALOG -> currentState
            else -> currentState // Stay in this state by default until a known transition occurs
        }
    }

    override fun enterState(
        context: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "Entering state. Screen: ${context.dasherScreen?.screenName}")
        wasClickAttempted = false // Reset flag on entering state

        // The goal is to find the dollar amount button and click it.
        // The button's text is the dollar amount itself.
        // We look for any text that starts with a '$'.
        val buttonText = context.screenTexts.find { it.trim().startsWith("$") }

        if (buttonText == null) {
            Log.w(
                tag,
                "Could not find a dollar amount button to click on the delivery completed screen."
            )
            return
        }

        Log.d(tag, "Found potential button text: '$buttonText'. Attempting to click.")

        Manager.getScope().launch {
            val clickSuccess =
                NodeActionUtils.findAndClickNodeByText(context.rootNode, buttonText.trim())
            if (clickSuccess) {
                Log.i(tag, "Successfully performed click on button with text: '$buttonText'")
                DashBuddyApplication.sendBubbleMessage("Pay button clicked!")
            } else {
                Log.w(tag, "Failed to perform click on button with text: '$buttonText'")
            }
            wasClickAttempted = true
        }
    }

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.i(tag, "Exiting state to $nextState")
        wasClickAttempted = false // Reset flag
    }

    // Using a helper function to contain matching logic makes it cleaner and easier to improve later
    private fun namesMatch(nameFromOffer: String, nameFromPickupScreen: String): Boolean {
        val normalizedOfferName = normalize(nameFromOffer)
        val normalizedPickupName = normalize(nameFromPickupScreen)

        // Check for containment in both directions to handle cases where one name is a subset of the other
        return normalizedOfferName.contains(normalizedPickupName)
                || normalizedPickupName.contains(normalizedOfferName)
    }

    // Normalizes a string for better matching by lowercasing and removing non-alphanumeric chars
    private fun normalize(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
}
