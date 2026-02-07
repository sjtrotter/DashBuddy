package cloud.trotter.dashbuddy.state.reducers.postdelivery

import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.AppStateV2.PostDelivery.Phase
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.DeliveryStateFactory
import cloud.trotter.dashbuddy.state.factories.PickupStateFactory
import cloud.trotter.dashbuddy.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostDeliveryReducer @Inject constructor(
    private val awaitingStateFactory: AwaitingStateFactory,
    private val deliveryStateFactory: DeliveryStateFactory,
    private val pickupStateFactory: PickupStateFactory,
) {

    fun reduce(state: AppStateV2.PostDelivery, input: ScreenInfo): Transition? {

        // 1. Check for Exits (Screen changes)
        // Just return the Factory result directly. It carries the correct newState.
        val exitTransition = when (input) {
            is ScreenInfo.WaitingForOffer ->
                awaitingStateFactory.createEntry(state, input, isRecovery = false)

            is ScreenInfo.DropoffDetails ->
                deliveryStateFactory.createEntry(state, input, isRecovery = false)

            is ScreenInfo.PickupDetails ->
                pickupStateFactory.createEntry(state, input, isRecovery = false)

            else -> null
        }

        if (exitTransition != null) return exitTransition

        // 2. Internal Phase Logic (The Robot)
        // These sub-objects (StabilizingPhase, etc.) must also return the new 'Transition' class.
        return when (state.phase) {
            Phase.STABILIZING -> StabilizingPhase.reduce(state, input)
            Phase.CLICKING -> ClickingPhase.reduce(state, input)
            Phase.VERIFYING -> VerifyingPhase.reduce(state, input)
            Phase.RECORDED -> RecordedPhase.reduce(state, input)
        }
    }

    fun onTimeout(state: AppStateV2.PostDelivery, event: TimeoutEvent): Transition {
        val internalUpdate = when (state.phase) {
            Phase.STABILIZING -> StabilizingPhase.onTimeout(state, event)
            Phase.VERIFYING -> VerifyingPhase.onTimeout(state, event)
            else -> null
        }
        return internalUpdate ?: Transition(state)
    }
}