package cloud.trotter.dashbuddy.state.reducers.offer

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.recognition.click.ClickAction
import cloud.trotter.dashbuddy.pipeline.recognition.screen.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.ClickEvent
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.DashPausedStateFactory
import cloud.trotter.dashbuddy.state.factories.OfferStateFactory
import cloud.trotter.dashbuddy.state.factories.PickupStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfferReducer @Inject constructor(
    private val offerStateFactory: OfferStateFactory,
    private val awaitingStateFactory: AwaitingStateFactory,
    private val pickupStateFactory: PickupStateFactory,
    private val dashPausedStateFactory: DashPausedStateFactory,
) {

    fun reduce(state: AppStateV2.OfferPresented, input: ScreenInfo): Transition? {

        // Helper to simplify logging + transitioning
        fun request(
            factoryResult: Transition,
            outcome: AppEventType,
            description: String
        ): Transition {
            // Create the definitive log entry for how this offer ended
            val logEvent = ReducerUtils.createEvent(
                state.dashId,
                outcome,
                description
                // Optional: metadata = Gson().toJson(state.clickInfo)
            )

            //TODO: remove this after testing.
            val bubbleMessage = if (outcome == AppEventType.OFFER_ACCEPTED) {
                "Offer Accepted"
            } else if (outcome == AppEventType.OFFER_DECLINED) {
                "Offer Declined"
            } else {
                "Offer Timed Out!"
            }

            return factoryResult.copy(
                effects = factoryResult.effects + AppEffect.LogEvent(logEvent)
                        // TODO: Remove this after testing.
                        + AppEffect.UpdateBubble(bubbleMessage, persona = ChatPersona.Dispatcher)
            )
        }

        return when (input) {
            // EXIT PATH 1: Replaced by New Offer
            is ScreenInfo.Offer -> {
                if (input.parsedOffer.offerHash != state.currentOfferHash) {
                    val outcome = resolveOutcome(state)
                    request(
                        offerStateFactory.createEntry(state, input, isRecovery = false),
                        outcome,
                        "Replaced by new offer"
                    )
                } else if (state.currentScreen != input.screen) {
                    Transition(state.copy(currentScreen = input.screen))
                } else {
                    Transition(state)
                }
            }

            // INTERNAL STATE: Modal Logic
            is ScreenInfo.Simple -> {
                if (input.screen == Screen.OFFER_POPUP_CONFIRM_DECLINE) {
                    if (state.currentScreen != Screen.OFFER_POPUP_CONFIRM_DECLINE) {
                        Transition(state.copy(currentScreen = Screen.OFFER_POPUP_CONFIRM_DECLINE))
                    } else {
                        Transition(state)
                    }
                } else {
                    null
                }
            }

            // EXIT PATH 2: Accepted (Pickup)
            is ScreenInfo.PickupDetails -> {
                val outcome = resolveOutcome(state)
                request(
                    pickupStateFactory.createEntry(state, input, isRecovery = false),
                    outcome,
                    "Transitioned to Pickup"
                )
            }

            // EXIT PATH 3: Search/Idle (Decline or Timeout)
            is ScreenInfo.WaitingForOffer -> {
                val outcome = resolveOutcome(state)
                request(
                    awaitingStateFactory.createEntry(state, input, isRecovery = false),
                    outcome,
                    "Returned to search"
                )
            }

            // EXIT PATH 4: Paused
            is ScreenInfo.DashPaused -> {
                val outcome = resolveOutcome(state)
                request(
                    dashPausedStateFactory.createEntry(state, input, isRecovery = false),
                    outcome,
                    "Dash Paused during offer"
                )
            }

            else -> null
        }
    }

    /**
     * Determines how the current offer ended based strictly on user intent (Clicks).
     * If the user clicked nothing, we default to TIMEOUT.
     * * Note: This handles "Add-on Timeout" correctly. If you are OnPickup, ignore an add-on,
     * and stay OnPickup, this returns TIMEOUT because you didn't click ACCEPT.
     */
    private fun resolveOutcome(state: AppStateV2.OfferPresented): AppEventType {
        val action = state.clickInfo?.second

        return when (action) {
            ClickAction.ACCEPT_OFFER -> AppEventType.OFFER_ACCEPTED
            ClickAction.DECLINE_OFFER -> AppEventType.OFFER_DECLINED
            else -> AppEventType.OFFER_TIMEOUT
        }
    }

    /**
     * Captures the user's intent. We don't change the screen here;
     * we just record "The user clicked Accept/Decline while on Screen X".
     */
    fun onClick(state: AppStateV2.OfferPresented, event: ClickEvent): Transition {
        val newClickInfo = state.currentScreen to event.action
        return Transition(
            newState = state.copy(clickInfo = newClickInfo)
        )
    }
}