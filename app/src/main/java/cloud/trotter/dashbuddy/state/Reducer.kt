package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.event.NotificationEvent
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.event.StateEvent
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.reducers.*
import cloud.trotter.dashbuddy.state.effects.NotificationHandler
import cloud.trotter.dashbuddy.state.event.OfferEvaluationEvent
import cloud.trotter.dashbuddy.state.reducers.offer.OfferReducer
import cloud.trotter.dashbuddy.state.reducers.postdelivery.PostDeliveryReducer
import cloud.trotter.dashbuddy.log.Logger as Log

object Reducer {

    data class Transition(
        val newState: AppStateV2,
        val effects: List<AppEffect> = emptyList()
    )

    fun reduce(currentState: AppStateV2, stateEvent: StateEvent): Transition {
        return when (stateEvent) {

            is OfferEvaluationEvent -> {
                // eventually, transition to sub phase of OfferReducer to take action
                return Transition(currentState)
            }

            is NotificationEvent -> {
                NotificationHandler.handle(currentState, stateEvent)
            }

            is TimeoutEvent -> {
                Log.i("Reducer", "Handling Timeout: ${stateEvent.type}")

                // Force transition to Idle (Dash Ended)
                return when (currentState) {
                    is AppStateV2.DashPaused ->
                        DashPausedReducer.onTimeout(state = currentState, type = stateEvent.type)

                    is AppStateV2.PostDelivery ->
                        PostDeliveryReducer.onTimeout(state = currentState, event = stateEvent)

                    else -> Transition(currentState)
                }
            }

            is ScreenUpdateEvent -> {
                val input = stateEvent.screenInfo ?: return Transition(currentState)
                if (input.screen == Screen.UNKNOWN) return Transition(currentState)

                // 1. SEQUENTIAL LOGIC (Specifics)
                // We now map EVERY state to its specific reducer
                val sequential = when (currentState) {
                    is AppStateV2.Initializing -> InitializingReducer.reduce(currentState, input)
                    is AppStateV2.IdleOffline -> IdleReducer.reduce(currentState, input)
                    is AppStateV2.AwaitingOffer -> AwaitingReducer.reduce(currentState, input)
                    is AppStateV2.OfferPresented -> OfferReducer.reduce(currentState, input)
                    is AppStateV2.OnPickup -> PickupReducer.reduce(currentState, input)
                    is AppStateV2.OnDelivery -> DeliveryReducer.reduce(currentState, input)
                    is AppStateV2.PostDelivery -> PostDeliveryReducer.reduce(currentState, input)
                    is AppStateV2.PostDash -> SummaryReducer.reduce(currentState, input)
                    is AppStateV2.DashPaused -> DashPausedReducer.reduce(currentState, input)
                    is AppStateV2.PausedOrInterrupted -> null // Let anchors catch us up
                }

                if (sequential != null) return sequential

                // 2. ANCHOR LOGIC (Global Fallback)
                val anchor = checkAnchors(currentState, input)

                if (anchor != null) {
                    val warning =
                        AppEffect.UpdateBubble("⚠️ State recovered via Anchor", isImportant = false)
                    return anchor.copy(effects = anchor.effects + warning)
                }

                // 3. STASIS
                return Transition(currentState)
            }
        }
    }

    private fun checkAnchors(state: AppStateV2, input: ScreenInfo): Transition? {
        return when (input) {
            // Anchor: Searching
            is ScreenInfo.WaitingForOffer -> {
                if (state !is AppStateV2.AwaitingOffer)
                    AwaitingReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: New Offer
            is ScreenInfo.Offer -> {
                if (state !is AppStateV2.OfferPresented)
                    OfferReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Offline/Map
            is ScreenInfo.IdleMap -> {
                if (state !is AppStateV2.IdleOffline && state !is AppStateV2.Initializing)
                    IdleReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Pickup Screen
            is ScreenInfo.PickupDetails -> {
                if (state !is AppStateV2.OnPickup)
                    PickupReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Dropoff Screen
            is ScreenInfo.DropoffDetails -> {
                if (state !is AppStateV2.OnDelivery)
                    DeliveryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Collapsed Delivery Summary
            is ScreenInfo.DeliverySummaryCollapsed -> {
                if (state !is AppStateV2.PostDelivery)
                    PostDeliveryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Payout Screen
            is ScreenInfo.DeliveryCompleted -> {
                if (state !is AppStateV2.PostDelivery)
                    PostDeliveryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: End of Dash Summary
            is ScreenInfo.DashSummary -> {
                if (state !is AppStateV2.PostDash)
                    SummaryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }

            is ScreenInfo.DashPaused -> {
                if (state !is AppStateV2.DashPaused)
                    DashPausedReducer.transitionTo(state, input, isRecovery = true)
                else null
            }

            else -> null
        }
    }
}