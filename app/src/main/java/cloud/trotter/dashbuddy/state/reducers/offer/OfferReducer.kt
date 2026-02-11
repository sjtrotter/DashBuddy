package cloud.trotter.dashbuddy.state.reducers.offer

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.accessibility.click.ClickAction
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.ClickEvent
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.DashPausedStateFactory
import cloud.trotter.dashbuddy.state.factories.OfferStateFactory
import cloud.trotter.dashbuddy.state.factories.PickupStateFactory
import cloud.trotter.dashbuddy.state.factories.PostDeliveryStateFactory
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
    private val postDeliveryStateFactory: PostDeliveryStateFactory,
) {

    fun reduce(state: AppStateV2.OfferPresented, input: ScreenInfo): Transition? {

        // Helper to simplify logging + transitioning
        fun request(
            factoryResult: Transition,
            outcome: AppEventType,
            description: String
        ): Transition {
            // 1. Log the event (Database) - Always happens on transition
            val logEvent = ReducerUtils.createEvent(
                state.dashId,
                outcome,
                description
            )

            // 2. Handle Feedback
            val effects = factoryResult.effects.toMutableList()
            effects.add(AppEffect.LogEvent(logEvent))

            // 3. Timeout Bubble Logic
            // If the outcome is TIMEOUT, the user never clicked anything,
            // so we haven't shown a bubble yet. We must show it now.
            // (If it was ACCEPT/DECLINE, we already showed it in onClick, so we skip it here)
            if (outcome == AppEventType.OFFER_TIMEOUT) {
                effects.add(
                    AppEffect.UpdateBubble(
                        "Offer Timed Out!",
                        persona = ChatPersona.Dispatcher
                    )
                )
            }

            return factoryResult.copy(effects = effects)
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

            // EXIT PATH 5: Return to Post-Delivery (e.g. Add-on Offer ignored)
            is ScreenInfo.DeliveryCompleted -> {
                val outcome = resolveOutcome(state)
                request(
                    postDeliveryStateFactory.createEntry(state, input, isRecovery = false),
                    outcome,
                    "return to DeliveryCompleted after offer"
                )
            }

            else -> null
        }
    }

    private fun resolveOutcome(state: AppStateV2.OfferPresented): AppEventType {
        val action = state.clickInfo?.second

        return when (action) {
            ClickAction.ACCEPT_OFFER -> AppEventType.OFFER_ACCEPTED
            ClickAction.DECLINE_OFFER -> AppEventType.OFFER_DECLINED
            else -> AppEventType.OFFER_TIMEOUT
        }
    }

    /**
     * Captures the user's intent AND provides immediate feedback (Optimistic UI).
     */
    fun onClick(state: AppStateV2.OfferPresented, event: ClickEvent): Transition {
        // 1. Update State (So resolveOutcome works later when the screen changes)
        val newClickInfo = state.currentScreen to event.action

        // 2. Immediate Feedback
        // We fire the bubble NOW. We don't wait for the app to navigate.
        val bubbleEffect = when (event.action) {
            ClickAction.ACCEPT_OFFER -> AppEffect.UpdateBubble(
                "Offer Accepted",
                persona = ChatPersona.Dispatcher
            )

            ClickAction.DECLINE_OFFER -> AppEffect.UpdateBubble(
                "Offer Declined",
                persona = ChatPersona.Dispatcher
            )

            else -> null
        }

        return Transition(
            newState = state.copy(clickInfo = newClickInfo),
            effects = listOfNotNull(bubbleEffect) // Fire immediately!
        )
    }
}