package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.effects.NotificationHandler
import cloud.trotter.dashbuddy.state.event.NotificationEvent
import cloud.trotter.dashbuddy.state.event.OfferEvaluationEvent
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.event.StateEvent
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.DashPausedStateFactory
import cloud.trotter.dashbuddy.state.factories.DeliveryStateFactory
import cloud.trotter.dashbuddy.state.factories.IdleStateFactory
import cloud.trotter.dashbuddy.state.factories.OfferStateFactory
import cloud.trotter.dashbuddy.state.factories.PickupStateFactory
import cloud.trotter.dashbuddy.state.factories.PostDeliveryStateFactory
import cloud.trotter.dashbuddy.state.factories.SummaryStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
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

@Singleton
class Reducer @Inject constructor(
    // Reducers (Logic)
    private val idleReducer: IdleReducer,
    private val awaitingReducer: AwaitingReducer,
    private val offerReducer: OfferReducer,
    private val pickupReducer: PickupReducer,
    private val deliveryReducer: DeliveryReducer,
    private val postDeliveryReducer: PostDeliveryReducer,
    private val summaryReducer: SummaryReducer,
    private val dashPausedReducer: DashPausedReducer,
    private val initializingReducer: InitializingReducer,

    // Factories (State Construction for Anchors)
    private val idleStateFactory: IdleStateFactory,
    private val awaitingStateFactory: AwaitingStateFactory,
    private val offerStateFactory: OfferStateFactory,
    private val pickupStateFactory: PickupStateFactory,
    private val deliveryStateFactory: DeliveryStateFactory,
    private val postDeliveryStateFactory: PostDeliveryStateFactory,
    private val summaryStateFactory: SummaryStateFactory,
    private val dashPausedStateFactory: DashPausedStateFactory,

    private val notificationHandler: NotificationHandler,
) {

    // Main entry point - acts as the Orchestrator
    fun reduce(currentState: AppStateV2, stateEvent: StateEvent): Transition {
        // Since we removed TransitionTo effect, we simply delegate logic.
        // The Child Reducers and Factories now return the 'newState' directly in the Transition object.
        return delegateReduce(currentState, stateEvent)
    }

    private fun delegateReduce(currentState: AppStateV2, stateEvent: StateEvent): Transition {
        return when (stateEvent) {
            is OfferEvaluationEvent -> Transition(currentState)

            is NotificationEvent -> notificationHandler.handle(currentState, stateEvent)

            is TimeoutEvent -> {
                Timber.i("Handling Timeout: ${stateEvent.type}")
                when (currentState) {
                    is AppStateV2.DashPaused ->
                        dashPausedReducer.onTimeout(currentState, stateEvent.type)

                    is AppStateV2.PostDelivery ->
                        postDeliveryReducer.onTimeout(currentState, stateEvent)

                    else -> Transition(currentState)
                }
            }

            is ScreenUpdateEvent -> {
                val input = stateEvent.screenInfo ?: return Transition(currentState)
                if (input.screen == Screen.UNKNOWN) return Transition(currentState)

                // A. Sequential Logic (Child Reducers)
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
                    is AppStateV2.PausedOrInterrupted -> null
                }

                if (sequential != null) return sequential

                // B. Anchor Logic (Global Recovery)
                // If specific reducer didn't handle it, check if we drifted to a known anchor state
                checkAnchors(currentState, input) ?: Transition(currentState)
            }
        }
    }

    private fun checkAnchors(state: AppStateV2, input: ScreenInfo): Transition? {
        // Helper to package the factory result into a Transition
        fun <T : AppStateV2> pack(
            factoryResult: Transition,
            targetClass: Class<T>
        ): Transition? {
            // Don't transition if we are already there (prevents infinite loops)
            if (targetClass.isInstance(state)) return null

            // Add the warning effect
            val warning = AppEffect.UpdateBubble("⚠️ State recovered via Anchor", expand = false)

            // Direct State Switch: Use the factory's new state immediately
            return Transition(
                newState = factoryResult.newState,
                effects = factoryResult.effects + warning
            )
        }

        return when (input) {
            is ScreenInfo.WaitingForOffer -> pack(
                awaitingStateFactory.createEntry(state, input, isRecovery = true),
                AppStateV2.AwaitingOffer::class.java
            )

            is ScreenInfo.Offer -> pack(
                offerStateFactory.createEntry(state, input, isRecovery = true),
                AppStateV2.OfferPresented::class.java
            )

            is ScreenInfo.IdleMap -> {
                // Special case: Initializing is allowed to stay as is
                if (state is AppStateV2.Initializing) return null
                pack(
                    idleStateFactory.createEntry(state, input, isRecovery = true),
                    AppStateV2.IdleOffline::class.java
                )
            }

            is ScreenInfo.PickupDetails -> pack(
                pickupStateFactory.createEntry(state, input, isRecovery = true),
                AppStateV2.OnPickup::class.java
            )

            is ScreenInfo.DropoffDetails -> pack(
                deliveryStateFactory.createEntry(state, input, isRecovery = true),
                AppStateV2.OnDelivery::class.java
            )

            is ScreenInfo.DeliveryCompleted -> pack(
                postDeliveryStateFactory.createEntry(state, input, isRecovery = true),
                AppStateV2.PostDelivery::class.java
            )

            is ScreenInfo.DeliverySummaryCollapsed -> pack(
                postDeliveryStateFactory.createEntry(state, input, isRecovery = true),
                AppStateV2.PostDelivery::class.java
            )

            is ScreenInfo.DashSummary -> pack(
                summaryStateFactory.createEntry(state, input, isRecovery = true),
                AppStateV2.PostDash::class.java
            )

            is ScreenInfo.DashPaused -> pack(
                dashPausedStateFactory.createEntry(state, input, isRecovery = true),
                AppStateV2.DashPaused::class.java
            )

            else -> null
        }
    }
}