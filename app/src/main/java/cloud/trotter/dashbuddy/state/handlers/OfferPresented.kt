package cloud.trotter.dashbuddy.state.handlers

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.event.OfferEventEntity
import cloud.trotter.dashbuddy.data.offer.OfferEvaluator
import cloud.trotter.dashbuddy.data.offer.OfferParser
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.state.StateHandler

class OfferPresented : StateHandler {

    private val tag = "OfferPresented"

    // Dependencies
    private val currentRepo = DashBuddyApplication.currentRepo

    // Assumes you've added this to DashBuddyApplication
    private val offerEventRepo = DashBuddyApplication.offerEventRepo

    // Deduplication State
    private var lastLoggedOfferHash: String? = null

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        val screen = stateContext.screenInfo?.screen ?: return currentState

        // If we are still on the offer screen, try to capture data (in case we missed it on entry)
        if (screen == Screen.OFFER_POPUP || screen == Screen.OFFER_POPUP_CONFIRM_DECLINE) {
            captureOfferEvent(stateContext)
            return currentState
        }

        // Transitions OUT of Offer Presented
        return when {
            screen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER ||
                    screen == Screen.ON_DASH_ALONG_THE_WAY -> AppState.DASH_ACTIVE_AWAITING_OFFER

            screen == Screen.DASH_CONTROL -> AppState.DASH_ACTIVE_ON_CONTROL
            screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_IDLE_OFFLINE
            screen == Screen.DELIVERY_SUMMARY_COLLAPSED -> AppState.DASH_ACTIVE_DELIVERY_COMPLETED
            screen == Screen.DELIVERY_SUMMARY_EXPANDED -> AppState.DASH_POST_DELIVERY

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
        Log.d(tag, "Entering OfferPresented state...")
        // Reset deduplication on entry so we always log the first frame of a new state entry
        lastLoggedOfferHash = null

        captureOfferEvent(stateContext)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun captureOfferEvent(stateContext: StateContext) {
        try {
            // 1. Parse the Offer
            val parsedOffer = OfferParser.parseOffer(stateContext.rootNodeTexts)
            if (parsedOffer == null) {
                // Not enough info to log yet (maybe screen is loading)
                return
            }

            // 2. Deduplication: Don't log the same offer frame after frame
            if (parsedOffer.offerHash == lastLoggedOfferHash) {
                return
            }

            Log.i(tag, "New Offer detected (Hash: ${parsedOffer.offerHash}). Logging Event.")

            // 3. Get Context (Dash ID / Zone)
            val current = currentRepo.getCurrentDashState()

            // 4. Evaluate (Optional: Use existing logic just for the Bubble/Score)
            // We use the evaluator to calculate the score/bubble message, but we DON'T save the OfferEntity it creates.
            if (current?.dashId != null && current.zoneId != null) {
                val evaluation = OfferEvaluator.evaluateOffer(
                    parsedOffer,
                    current.dashId,
                    current.zoneId,
                    stateContext.timestamp
                )
                // Send the bubble for user feedback
                DashBuddyApplication.sendBubbleMessage(evaluation.bubbleMessage)
            }

            // 5. Create and Insert the Event
            val event = OfferEventEntity(
                dashId = current?.dashId,
                offerHash = parsedOffer.offerHash,
                payAmount = parsedOffer.payAmount,
                distanceMiles = parsedOffer.distanceMiles,
                rawText = parsedOffer.rawExtractedTexts,
                odometerReading = stateContext.odometerReading
            )

            offerEventRepo.insert(event)

            // Update memory
            lastLoggedOfferHash = parsedOffer.offerHash

        } catch (e: Exception) {
            Log.e(tag, "Error capturing offer event", e)
        }
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.d(tag, "Exiting OfferPresented state.")
        // Clear memory
        lastLoggedOfferHash = null
    }
}