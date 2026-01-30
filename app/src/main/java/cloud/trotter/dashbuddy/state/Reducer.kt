package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.effects.NotificationHandler
import cloud.trotter.dashbuddy.state.event.NotificationEvent
import cloud.trotter.dashbuddy.state.event.OfferEvaluationEvent
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.event.StateEvent
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.reducers.AwaitingReducer
import cloud.trotter.dashbuddy.state.reducers.DashPausedReducer
import cloud.trotter.dashbuddy.state.reducers.DeliveryReducer
import cloud.trotter.dashbuddy.state.reducers.IdleReducer
import cloud.trotter.dashbuddy.state.reducers.InitializingReducer
import cloud.trotter.dashbuddy.state.reducers.PickupReducer
import cloud.trotter.dashbuddy.state.reducers.SummaryReducer
import cloud.trotter.dashbuddy.state.reducers.offer.OfferReducer
import cloud.trotter.dashbuddy.state.reducers.postdelivery.PostDeliveryReducer
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

//import cloud.trotter.dashbuddy.log.Logger as Log

@Singleton
class Reducer @Inject constructor(
    private val idleReducer: IdleReducer,
    private val awaitingReducer: AwaitingReducer,
    private val offerReducer: OfferReducer,
    private val pickupReducer: PickupReducer,
    private val deliveryReducer: DeliveryReducer,
    private val postDeliveryReducer: PostDeliveryReducer,
    private val summaryReducer: SummaryReducer,
    private val dashPausedReducer: DashPausedReducer,
    private val initializingReducer: InitializingReducer
) {

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
                Timber.i("Handling Timeout: ${stateEvent.type}")

                // Force transition to Idle (Dash Ended)
                return when (currentState) {
                    is AppStateV2.DashPaused ->
                        dashPausedReducer.onTimeout(state = currentState, type = stateEvent.type)

                    is AppStateV2.PostDelivery ->
                        postDeliveryReducer.onTimeout(state = currentState, event = stateEvent)

                    else -> Transition(currentState)
                }
            }

            is ScreenUpdateEvent -> {
                val input = stateEvent.screenInfo ?: return Transition(currentState)
                if (input.screen == Screen.UNKNOWN) return Transition(currentState)

                // 1. SEQUENTIAL LOGIC (Specifics)
                // We now map EVERY state to its specific reducer
                val sequential = when (currentState) {
                    is AppStateV2.Initializing -> initializingReducer.reduce(currentState, input)
                    is AppStateV2.IdleOffline -> idleReducer.reduce(currentState, input)
                    is AppStateV2.AwaitingOffer -> awaitingReducer.reduce(currentState, input)
                    is AppStateV2.OfferPresented -> offerReducer.reduce(currentState, input)
                    is AppStateV2.OnPickup -> pickupReducer.reduce(currentState, input)
                    is AppStateV2.OnDelivery -> deliveryReducer.reduce(currentState, input)
                    is AppStateV2.PostDelivery -> postDeliveryReducer.reduce(currentState, input)
                    is AppStateV2.PostDash -> summaryReducer.reduce(currentState, input)
                    is AppStateV2.DashPaused -> dashPausedReducer.reduce(currentState, input)
                    is AppStateV2.PausedOrInterrupted -> null // Let anchors catch us up
                }

                if (sequential != null) return sequential

                // 2. ANCHOR LOGIC (Global Fallback)
                val anchor = checkAnchors(currentState, input)

                if (anchor != null) {
                    val warning =
                        AppEffect.UpdateBubble("⚠️ State recovered via Anchor", expand = false)
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
                    awaitingReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: New Offer
            is ScreenInfo.Offer -> {
                if (state !is AppStateV2.OfferPresented)
                    offerReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Offline/Map
            is ScreenInfo.IdleMap -> {
                if (state !is AppStateV2.IdleOffline && state !is AppStateV2.Initializing)
                    idleReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Pickup Screen
            is ScreenInfo.PickupDetails -> {
                if (state !is AppStateV2.OnPickup)
                    pickupReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Dropoff Screen
            is ScreenInfo.DropoffDetails -> {
                if (state !is AppStateV2.OnDelivery)
                    deliveryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Collapsed Delivery Summary
            is ScreenInfo.DeliverySummaryCollapsed -> {
                if (state !is AppStateV2.PostDelivery)
                    postDeliveryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Payout Screen
            is ScreenInfo.DeliveryCompleted -> {
                if (state !is AppStateV2.PostDelivery)
                    postDeliveryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: End of Dash Summary
            is ScreenInfo.DashSummary -> {
                if (state !is AppStateV2.PostDash)
                    summaryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }

            is ScreenInfo.DashPaused -> {
                if (state !is AppStateV2.DashPaused)
                    dashPausedReducer.transitionTo(state, input, isRecovery = true)
                else null
            }

            else -> null
        }
    }
}