package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.offer.OfferEvaluator // Assuming this is an object
import cloud.trotter.dashbuddy.data.offer.OfferParser // Assuming this is an object
import cloud.trotter.dashbuddy.state.Manager
import cloud.trotter.dashbuddy.log.Logger as Log // Your Logger alias
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen
import kotlinx.coroutines.launch
import java.util.Locale

class OfferPresented : StateHandler {

    private var internalOfferId: Long? = null

    private val currentRepo = DashBuddyApplication.currentRepo
    private val offerRepo = DashBuddyApplication.offerRepo
    private val orderRepo = DashBuddyApplication.orderRepo

    // Define a TAG for logging within this class
    private val tag = this::class.simpleName ?: "OfferPresented"


    override fun processEvent(context: StateContext, currentState: AppState): AppState {
        Log.d(tag, "Evaluating state for event...")

        // --- Handle "Accept" click specifically within this state ---
        if (internalOfferId != null && context.eventTypeString == "TYPE_VIEW_CLICKED") {
            if (context.sourceNodeTexts.any { it.equals("Accept", ignoreCase = true) }) {
                Log.i(tag, "Offer 'Accept' button clicked for offer ID: $internalOfferId")
                Manager.getScope().launch {
                    try {
                        offerRepo.updateOfferStatus(internalOfferId!!, "ACCEPTED")
                        Log.i(tag, "Offer status updated to ACCEPTED for ID: $internalOfferId")
                        DashBuddyApplication.sendBubbleMessage("Offer Accepted!")
                    } catch (e: Exception) {
                        Log.e(tag, "!!! Could not mark offer as ACCEPTED: $internalOfferId !!!", e)
                        DashBuddyApplication.sendBubbleMessage("Error marking offer accepted!\nCheck logs.")
                    }
                }
            } else if (context.sourceNodeTexts.any {
                    it.equals(
                        "Decline offer",
                        ignoreCase = true
                    )
                }) {
                Log.i(tag, "Offer 'Decline' button clicked for offer ID: $internalOfferId")
                Manager.getScope().launch {
                    try {
                        offerRepo.updateOfferStatus(internalOfferId!!, "DECLINED")
                        Log.i(tag, "Offer status updated to DECLINED for ID: $internalOfferId")
                        DashBuddyApplication.sendBubbleMessage("Offer Declined!")
                    } catch (e: Exception) {
                        Log.e(tag, "!!! Could not mark offer as DECLINED: $internalOfferId !!!", e)
                        DashBuddyApplication.sendBubbleMessage("Error marking offer declined!\nCheck logs.")
                    }
                }
            }
        }
        // --- End of Accept click handling ---

        // Determine next state based on current screen
        return when (context.dasherScreen) {
            Screen.ON_DASH_ALONG_THE_WAY -> AppState.SESSION_ACTIVE_DASHING_ALONG_THE_WAY
            Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
            Screen.DASH_CONTROL -> AppState.VIEWING_DASH_CONTROL
            Screen.MAIN_MAP_IDLE -> AppState.DASHER_IDLE_OFFLINE
            Screen.NAVIGATION_VIEW -> AppState.VIEWING_NAVIGATION
            else -> currentState // Stay in current state if no specific transition
        }
    }

    override fun enterState(
        context: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d(tag, "Entering state...")
        internalOfferId = null

        Manager.getScope().launch {
            try {
                Log.d(tag, "Starting offer processing coroutine.")

                // Get current dash state
                val current: CurrentEntity? = currentRepo.getCurrentDashState()
                if (current?.dashId == null || current.zoneId == null) {
                    Log.w(
                        tag,
                        "Cannot process offer: Missing current dashId or zoneId. Current: $current"
                    )
                    // Potentially send a bubble message about missing current dash info
                    return@launch
                }
                Log.d(tag, "Current dash state: $current")

                // Parse the offer from screen texts
                Log.d(tag, "Parsing offer from screen texts.")
                val parsedOffer = OfferParser.parseOffer(context.screenTexts)

                if (parsedOffer == null) {
                    Log.w(tag, "!!! OfferParser returned null. Offer was not parsed!")
                    // Potentially send a bubble message about parsing failure
                    return@launch
                }
                Log.d(tag, "Offer parsed successfully: ${parsedOffer.offerHash}")

                // Check if this offer already exists
                Log.d(
                    tag,
                    "Querying for existing offer by hash: ${parsedOffer.offerHash}, dash: ${current.dashId}, zone: ${current.zoneId}"
                )
                val existingOffer = offerRepo.getOfferByDashZoneAndHash(
                    current.dashId,
                    current.zoneId,
                    parsedOffer.offerHash
                )

                if (existingOffer == null) {
                    Log.i(tag, "New offer detected. Evaluating and inserting.")
                    // Score offer and build OfferEntity
                    val offerToInsert: OfferEntity = OfferEvaluator.evaluateOffer(
                        parsedOffer = parsedOffer,
                        dashId = current.dashId,
                        zoneId = current.zoneId,
                        eventTimestamp = context.timestamp
                    )
                    Log.d(tag, "Offer evaluated. Attempting to insert: ${offerToInsert.offerHash}")

                    val newOfferId = offerRepo.insertOffer(offerToInsert)
                    internalOfferId = newOfferId

                    if (newOfferId > 0) {
                        Log.i(
                            tag,
                            "Offer inserted successfully with ID: $newOfferId. Score: ${offerToInsert.calculatedScore}, Quality: ${offerToInsert.scoreText}"
                        )
                        DashBuddyApplication.sendBubbleMessage(
                            "New Offer: ${offerToInsert.scoreText ?: "N/A"}\nScore: ${
                                String.format(
                                    Locale.US, "%.1f", offerToInsert.calculatedScore ?: 0.0
                                )
                            }"
                        )

                        // Insert orders associated with the offer
                        if (parsedOffer.orders.isNotEmpty()) {
                            Log.d(
                                tag,
                                "Inserting ${parsedOffer.orders.size} orders for offer ID: $newOfferId."
                            )
                            val orderEntitiesToInsert = parsedOffer.orders.map { parsedOrder ->
                                parsedOrder.toOrderEntity(offerId = newOfferId)
                            }
                            orderRepo.insertOrders(orderEntitiesToInsert)
                            Log.i(tag, "${orderEntitiesToInsert.size} orders inserted.")
                        } else {
                            Log.i(tag, "No individual orders found in parsedOffer to insert.")
                        }
                    } else {
                        Log.e(tag, "Failed to insert offer. Received ID: $newOfferId")
                        // Potentially send error bubble message
                    }
                    // update last offer info in current table
                    currentRepo.updateLastOfferInfo(
                        lastOfferId = internalOfferId,
                        lastOfferValue = offerToInsert.payAmount,
                    )
                } else {
                    Log.i(
                        tag,
                        "Offer already exists with ID: ${existingOffer.id}. Hash: ${parsedOffer.offerHash}"
                    )
                    internalOfferId = existingOffer.id
                    // Optionally, send a bubble message indicating offer was already seen
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
                // Optionally, send a bubble message about the error
                DashBuddyApplication.sendBubbleMessage("Error processing offer!\nCheck logs.")
            }
        }
        // DashBuddyApplication.sendBubbleMessage("${currentState.displayName} State\n${context.dasherScreen?.screenName} Screen")
    }

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.d(tag, "Exiting state...")
        // Resetting offerIdInternal here is good if this instance might be reused.
        internalOfferId = null
    }
}
