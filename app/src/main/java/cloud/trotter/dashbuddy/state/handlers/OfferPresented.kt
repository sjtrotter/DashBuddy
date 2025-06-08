package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.offer.OfferEvaluator
import cloud.trotter.dashbuddy.data.offer.OfferParser
import cloud.trotter.dashbuddy.state.Manager
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

class OfferPresented : StateHandler {

    private var internalOfferId: Long? = null
    private var offerDecided: Boolean = false

    private val currentRepo = DashBuddyApplication.currentRepo
    private val offerRepo = DashBuddyApplication.offerRepo
    private val orderRepo = DashBuddyApplication.orderRepo

    private val tag = this::class.simpleName ?: "OfferPresented"


    override fun processEvent(context: StateContext, currentState: AppState): AppState {
        Log.d(tag, "Evaluating state for event...")

        if (internalOfferId != null && context.eventTypeString == "TYPE_VIEW_CLICKED") {
            // --- Logic for Accept click ---
            if (context.sourceNodeTexts.any {
                    it.equals("Accept", ignoreCase = true) ||
                            it.equals("Add to route", ignoreCase = true)
                }) {
                Log.i(tag, "'Accept/Add to Route' button clicked for offer ID: $internalOfferId")
                offerDecided = true
                Manager.getScope().launch {
                    try {
                        offerRepo.updateOfferStatus(internalOfferId!!, "ACCEPTED")
                        Log.i(tag, "Offer status updated to ACCEPTED for ID: $internalOfferId")
                        DashBuddyApplication.sendBubbleMessage("Offer Accepted!")
                    } catch (e: Exception) {
                        Log.e(tag, "!!! Could not mark offer as ACCEPTED: $internalOfferId !!!", e)
                    }
                }
            }
            // --- Logic for Decline click ---
            else if (context.sourceNodeTexts.any {
                    it.equals("Decline offer", ignoreCase = true)
                }) {
                Log.i(tag, "'Decline offer' button clicked for offer ID: $internalOfferId")
                offerDecided = true
                Manager.getScope().launch {
                    try {
                        offerRepo.updateOfferStatus(
                            internalOfferId!!,
                            "DECLINED_USER"
                        )
                        Log.i(tag, "Offer status updated to DECLINED_USER for ID: $internalOfferId")
                        DashBuddyApplication.sendBubbleMessage("Offer Declined!")
                    } catch (e: Exception) {
                        Log.e(
                            tag,
                            "!!! Could not mark offer as DECLINED_USER: $internalOfferId !!!",
                            e
                        )
                    }
                }
            }
            // --- Logic for Decline Order (CoD) click ---
            else if (context.sourceNodeTexts.any {
                    it.equals("Decline order", ignoreCase = true)
                }) {
                Log.i(tag, "'Decline Order' button clicked for offer ID: $internalOfferId")
                offerDecided = true
                Manager.getScope().launch {
                    try {
                        // For CoD declines, you might want a specific status rather than deleting
                        // offerRepo.updateOfferStatus(internalOfferId!!, "DECLINED_COD")
                        // Or keep delete logic if that's preferred
                        offerRepo.getOfferById(internalOfferId!!)?.let { offerRepo.deleteOffer(it) }
                        currentRepo.updateLastOfferInfo(lastOfferId = null, lastOfferValue = null)
                        Log.i(tag, "Offer DELETED for ID: $internalOfferId")
                        internalOfferId = null
                        DashBuddyApplication.sendBubbleMessage("Order Declined!")
                    } catch (e: Exception) {
                        Log.e(tag, "!!! Could not DELETE offer: $internalOfferId !!!", e)
                    }
                }
            }
        }

        // Determine next state based on screen changes
        return when (context.dasherScreen) {
            Screen.ON_DASH_ALONG_THE_WAY -> AppState.SESSION_ACTIVE_DASHING_ALONG_THE_WAY
            Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
            Screen.DASH_CONTROL -> AppState.VIEWING_DASH_CONTROL
            Screen.MAIN_MAP_IDLE -> AppState.DASHER_IDLE_OFFLINE
            Screen.NAVIGATION_VIEW -> AppState.VIEWING_NAVIGATION
            else -> currentState // As long as it's an OFFER_POPUP, stay in this state
        }
    }

    override fun enterState(
        context: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d(tag, "Entering state...")
        // Reset flags for the new offer presentation
        internalOfferId = null
        offerDecided = false

        Manager.getScope().launch {
            try {
                // ... (your existing parsing and inserting logic remains the same) ...
                Log.d(tag, "Starting offer processing coroutine.")
                val current: CurrentEntity? = currentRepo.getCurrentDashState()
                if (current?.dashId == null || current.zoneId == null) {
                    Log.w(tag, "Cannot process offer: Missing current dashId or zoneId.")
                    return@launch
                }
                val parsedOffer = OfferParser.parseOffer(context.screenTexts)
                if (parsedOffer == null) {
                    Log.w(tag, "!!! OfferParser returned null. Offer was not parsed!")
                    return@launch
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
                        context.timestamp
                    )
                    val offerToInsert: OfferEntity = evaluationResult.offerEntity
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
    }

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.d(tag, "Exiting state...")

        // --- TIMEOUT LOGIC ---
        if (!offerDecided && internalOfferId != null) {
            Log.i(
                tag,
                "Exiting without a decision. Marking offer ID $internalOfferId as MISSED."
            )
            Manager.getScope().launch {
                try {
                    offerRepo.updateOfferStatus(internalOfferId!!, "MISSED_TIMEOUT")
                } catch (e: Exception) {
                    Log.e(
                        tag,
                        "!!! Could not mark offer as MISSED_TIMEOUT: $internalOfferId !!!",
                        e
                    )
                }
            }
        }

        // --- DECISION LOGIC ---
        // If an offer was decided, update the CurrentEntity state accordingly.
        if (offerDecided && internalOfferId != null) {
            Manager.getScope().launch {
                try {
                    val offer = offerRepo.getOfferById(internalOfferId!!)
                    if (offer == null) {
                        Log.w(
                            tag,
                            "Offer with ID $internalOfferId not found, can't update current state."
                        )
                        return@launch
                    }

                    when (offer.status) {
                        "ACCEPTED" -> {
                            Log.d(tag, "Offer ACCEPTED. Updating current dash state.")
                            // Increment the accepted counter
                            currentRepo.incrementOffersAccepted()

                            // Get associated order IDs and add them to the queue
                            val orders = orderRepo.getOrdersForOffer(internalOfferId!!).first()
                            if (orders.isNotEmpty()) {
                                val orderIds = orders.map { it.id }
                                currentRepo.addOrdersToQueue(orderIds)
                                Log.i(tag, "Added order IDs $orderIds to the active queue.")
                            } else {
                                Log.w(
                                    tag,
                                    "Accepted offer has no associated orders to add to queue."
                                )
                            }
                        }

                        "DECLINED_USER" -> {
                            Log.d(tag, "Offer DECLINED by user. Updating current dash state.")
                            // Increment the declined counter
                            currentRepo.incrementOffersDeclined()
                        }

                        else -> {
                            // No state change needed for other statuses like DECLINED_COD, etc.
                            Log.d(
                                tag,
                                "Exiting with status '${offer.status}', no counter update needed."
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(
                        tag,
                        "!!! Failed to update CurrentEntity for offer ID: $internalOfferId !!!",
                        e
                    )
                }
            }
        }

        // Reset state for the next time this handler is used
        internalOfferId = null
        offerDecided = false
    }
}
