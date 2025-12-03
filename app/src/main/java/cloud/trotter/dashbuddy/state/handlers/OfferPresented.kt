package cloud.trotter.dashbuddy.state.handlers

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.offer.OfferEvaluator
import cloud.trotter.dashbuddy.data.offer.OfferParser
import cloud.trotter.dashbuddy.data.offer.OfferStatus
import cloud.trotter.dashbuddy.data.order.OrderStatus
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import kotlinx.coroutines.flow.first
import java.util.Locale

class OfferPresented : StateHandler {

    private var internalOfferId: Long? = null
    private var screenClicked: Screen? = null
    private var previousScreen: Screen? = null
    private var isClicked: Boolean = false

    private val currentRepo = DashBuddyApplication.currentRepo
    private val offerRepo = DashBuddyApplication.offerRepo
    private val orderRepo = DashBuddyApplication.orderRepo

    private val tag = this::class.simpleName ?: "OfferPresented"


    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        Log.d(tag, "Evaluating state for event...")

        val screen = stateContext.screenInfo?.screen ?: return currentState

        if (stateContext.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && !isClicked) {
            isClicked = true
            screenClicked = previousScreen
            Log.d(tag, "Clicked on screen: $screenClicked")
        }

        // reset the click if the screen changes but we are still in the offer flow.
        val stillInOfferFlow =
            (screen == Screen.OFFER_POPUP || screen == Screen.OFFER_POPUP_CONFIRM_DECLINE)
        if (stillInOfferFlow && isClicked && (screenClicked != screen)) {
            isClicked = false
            screenClicked = null
            previousScreen = screen
            return currentState
        }

        // Determine next state based on screen changes
        previousScreen = screen
        return when {
            screen ==
                    Screen.ON_DASH_MAP_WAITING_FOR_OFFER
                    || screen == Screen.ON_DASH_ALONG_THE_WAY ->
                AppState.DASH_ACTIVE_AWAITING_OFFER

            screen == Screen.DASH_CONTROL -> AppState.DASH_ACTIVE_ON_CONTROL
            screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_IDLE_OFFLINE
            screen == Screen.DELIVERY_COMPLETED_DIALOG -> AppState.DASH_ACTIVE_DELIVERY_COMPLETED

            screen.isNavigating -> AppState.DASH_ACTIVE_ON_NAVIGATION
            screen.isPickup -> AppState.DASH_ACTIVE_ON_PICKUP
            screen.isDelivery -> AppState.DASH_ACTIVE_ON_DELIVERY
            else -> currentState
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d(tag, "Entering state...")
        // Reset flags for the new offer presentation
        internalOfferId = null
        screenClicked = null
        screenClicked = null
        previousScreen = null
        isClicked = false

        try {
            Log.d(tag, "Starting offer processing.")
            val current: CurrentEntity? = currentRepo.getCurrentDashState()
            if (current?.dashId == null || current.zoneId == null) {
                Log.w(tag, "Cannot process offer: Missing current dashId or zoneId.")
                return
            }
            val parsedOffer = OfferParser.parseOffer(stateContext.rootNodeTexts)
            if (parsedOffer == null) {
                Log.w(tag, "!!! OfferParser returned null. Offer was not parsed!")
                return
            }
            val existingOffer = offerRepo.getOfferByDashZoneAndHash(
                current.dashId,
                current.zoneId,
                parsedOffer.offerHash
            )
            if (existingOffer == null) {
                Log.i(tag, "New offer detected. Evaluating and inserting.")
                val evaluationResult = OfferEvaluator.evaluateOffer(
                    parsedOffer,
                    current.dashId,
                    current.zoneId,
                    stateContext.timestamp
                )
                val offerToInsert: OfferEntity = evaluationResult.offerEntity.copy(
                    odometerReading = stateContext.odometerReading
                )
                val newOfferId = offerRepo.insertOffer(offerToInsert)
                internalOfferId = newOfferId
                if (newOfferId > 0) {
                    Log.i(tag, "Offer inserted with ID: $newOfferId.")
                    DashBuddyApplication.sendBubbleMessage(evaluationResult.bubbleMessage)
                    if (parsedOffer.orders.isNotEmpty()) {
                        val orderEntitiesToInsert =
                            parsedOffer.orders.map { it.toOrderEntity(offerId = newOfferId) }
                        orderRepo.insertOrders(orderEntitiesToInsert)
                    }
                }
                currentRepo.updateLastOfferInfo(
                    lastOfferId = internalOfferId,
                    lastOfferValue = offerToInsert.payAmount
                )
            } else {
                Log.i(tag, "Offer already exists with ID: ${existingOffer.id}.")
                internalOfferId = existingOffer.id
                DashBuddyApplication.sendBubbleMessage(
                    "Seen Before: ${existingOffer.scoreText}\nScore: ${
                        String.format(
                            Locale.US,
                            "%.1f",
                            existingOffer.calculatedScore
                        )
                    }"
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "!!! CRITICAL ERROR during offer processing !!!", e)
            DashBuddyApplication.sendBubbleMessage("Error processing offer!\nCheck logs.")
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.d(tag, "Exiting state...")

        if (internalOfferId == null) {
            Log.d(tag, "Offer ID is null. Can't update a null offer. Returning.")
            return
        }

        val finalOfferId: Long = internalOfferId!!

        try {
            if (isClicked) {
                // we logged a click while in the offer flow.
                if (screenClicked == Screen.OFFER_POPUP) {
                    // we accepted the offer.
                    offerRepo.updateOfferStatus(finalOfferId, OfferStatus.ACCEPTED)
                    offerRepo.updateOfferAcceptTime(finalOfferId, stateContext.timestamp)
                    Log.i(tag, "Offer #$finalOfferId accepted.")
                    // add the orders to the queue.
                    val orders = orderRepo.getOrdersForOffer(finalOfferId).first()
                    if (orders.isNotEmpty()) {
                        val orderIds = orders.map { it.id }
                        currentRepo.addOrdersToQueue(orderIds)
                        Log.i(tag, "Added order IDs $orderIds to the active queue.")
                        for (orderId in orderIds) {
                            orderRepo.updateOrderStatus(orderId, OrderStatus.PENDING)
                        }
                    }
                } else if (screenClicked == Screen.OFFER_POPUP_CONFIRM_DECLINE) {
                    // we declined the offer.
                    offerRepo.updateOfferStatus(finalOfferId, OfferStatus.DECLINED_USER)
                    Log.i(tag, "Offer #$finalOfferId declined by user.")
                }

            } else {
                // we didn't log a click while in the offer flow.
                offerRepo.updateOfferStatus(finalOfferId, OfferStatus.DECLINED_TIMEOUT)
                Log.i(tag, "Offer #$finalOfferId declined by timeout.")
            }
        } catch (e: Exception) {
            Log.e(tag, "!!! CRITICAL ERROR during offer processing !!!", e)
            DashBuddyApplication.sendBubbleMessage("Error processing offer!\nCheck logs.")
        }

        // Reset state for the next time this handler is used
        internalOfferId = null
        screenClicked = null
        previousScreen = null
        isClicked = false
    }
}
