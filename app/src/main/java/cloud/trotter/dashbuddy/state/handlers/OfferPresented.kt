package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.offer.OfferEvaluator
import cloud.trotter.dashbuddy.data.offer.OfferParser
import cloud.trotter.dashbuddy.data.offer.OfferStatus
import cloud.trotter.dashbuddy.state.Manager
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen
import kotlinx.coroutines.flow.first
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
                Manager.enqueueDbWork {
                    try {
                        offerRepo.updateOfferStatus(internalOfferId!!, OfferStatus.ACCEPTED)
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
                Manager.enqueueDbWork {
                    try {
                        offerRepo.updateOfferStatus(
                            internalOfferId!!,
                            OfferStatus.DECLINED_USER
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
                Manager.enqueueDbWork {
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
            Screen.PICKUP_DETAILS_VIEW_BEFORE_ARRIVAL -> AppState.VIEWING_PICKUP_DETAILS
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

        Manager.enqueueDbWork {
            try {
                // ... (your existing parsing and inserting logic remains the same) ...
                Log.d(tag, "Starting offer processing coroutine.")
                val current: CurrentEntity? = currentRepo.getCurrentDashState()
                if (current?.dashId == null || current.zoneId == null) {
                    Log.w(tag, "Cannot process offer: Missing current dashId or zoneId.")
                    return@enqueueDbWork
                }
                val parsedOffer = OfferParser.parseOffer(context.rootNodeTexts)
                if (parsedOffer == null) {
                    Log.w(tag, "!!! OfferParser returned null. Offer was not parsed!")
                    return@enqueueDbWork
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
        // Capture the state into local variables BEFORE launching the coroutines.
        val decided = offerDecided
        val finalOfferId = internalOfferId
        val exitTime = context.timestamp

        // --- TIMEOUT CHECK ---
        if (!decided && finalOfferId != null) {

            Manager.enqueueDbWork {
                try {
                    val offer = offerRepo.getOfferById(finalOfferId)
                    if (offer == null || offer.status != OfferStatus.SEEN) {
                        // If the offer doesn't exist or was already handled by another process (like a failsafe),
                        // there's nothing for us to do.
                        return@enqueueDbWork
                    }

                    // Calculate the exact time the offer was set to expire.
                    // We add a small 1-second buffer to account for system lag.
                    val expirationTime =
                        offer.timestamp + ((offer.initialCountdownSeconds ?: 0) * 1000) + 1000

                    // Only if the current time is past the expiration time do we mark it as timed out.
                    if (exitTime >= expirationTime) {
                        Log.i(
                            tag,
                            "Offer #${finalOfferId} has expired. Marking as DECLINED_TIMEOUT."
                        )
                        DashBuddyApplication.sendBubbleMessage("Offer Expired!")
                        offerRepo.updateOfferStatus(finalOfferId, OfferStatus.DECLINED_TIMEOUT)
                        currentRepo.incrementOffersDeclined() // Still counts as a decline
                    } else {
                        // If we are exiting the state BEFORE the timer expired, it means the
                        // user navigated away or an informational click changed the screen.
                        // In this case, we do NOTHING. The offer status remains SEEN.
                        Log.i(
                            tag,
                            "Exiting state for Offer #${finalOfferId} before timeout. Status remains SEEN."
                        )
                    }

                } catch (e: Exception) {
                    Log.e(tag, "!!! Could not check timeout status for offer: $finalOfferId !!!", e)
                }
            }
        }

        // --- DECISION LOGIC ---
        if (decided && finalOfferId != null) {
            Manager.enqueueDbWork {
                try {
                    // Now, use the local variables inside the coroutine.
                    val offer = offerRepo.getOfferById(finalOfferId)
                    if (offer == null) {
                        Log.w(
                            tag,
                            "Offer with ID $finalOfferId not found, can't update current state."
                        )
                        return@enqueueDbWork
                    }

                    when (offer.status) {
                        OfferStatus.ACCEPTED -> {
                            Log.d(
                                tag,
                                "Offer ACCEPTED. Updating current dash state for offer ID $finalOfferId."
                            )
                            currentRepo.incrementOffersAccepted()
                            val orders = orderRepo.getOrdersForOffer(finalOfferId).first()
                            if (orders.isNotEmpty()) {
                                val orderIds = orders.map { it.id }
                                currentRepo.addOrdersToQueue(orderIds)
                                Log.i(tag, "Added order IDs $orderIds to the active queue.")
                            }
                        }

                        OfferStatus.DECLINED_USER -> {
                            Log.d(tag, "Offer DECLINED by user. Updating current dash state.")
                            currentRepo.incrementOffersDeclined()
                        }

                        else -> {
                            Log.d(
                                tag,
                                "Exiting with status '${offer.status}', no counter update needed."
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(
                        tag,
                        "!!! Failed to update CurrentEntity for offer ID: $finalOfferId !!!",
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
