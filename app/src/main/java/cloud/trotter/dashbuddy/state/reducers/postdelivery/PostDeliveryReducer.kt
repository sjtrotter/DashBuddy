package cloud.trotter.dashbuddy.state.reducers.postdelivery

import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.AppStateV2.PostDelivery.Phase
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.reducers.AwaitingReducer
import cloud.trotter.dashbuddy.state.reducers.DeliveryReducer
import cloud.trotter.dashbuddy.state.reducers.PickupReducer
import cloud.trotter.dashbuddy.log.Logger as Log

object PostDeliveryReducer {

    // --- FACTORY (Unified Entry) ---

    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo,
        isRecovery: Boolean
    ): Reducer.Transition {
        return when (input) {

            // Case A: Standard Flow (Collapsed) -> Start Waiting
            is ScreenInfo.DeliverySummaryCollapsed -> {
                StabilizingPhase.transitionTo(oldState, input)
            }

            // Case B: Manual Flow (Expanded) -> Skip to Verifying
            is ScreenInfo.DeliveryCompleted -> {
                VerifyingPhase.transitionTo(
                    oldState = AppStateV2.PostDelivery(dashId = oldState.dashId),
                    input = input,
                    clickSent = false // User did it manually
                )
            }

            else -> {
                Log.e("PostDelivery", "Invalid entry: ${input::class.simpleName}")
                Reducer.Transition(oldState)
            }
        }
    }

    // --- MAIN ROUTER ---

    fun reduce(state: AppStateV2.PostDelivery, input: ScreenInfo): Reducer.Transition? {

        val screenCheck = when (input) {
            is ScreenInfo.WaitingForOffer ->
                AwaitingReducer.transitionTo(state, input, isRecovery = false)

            is ScreenInfo.DropoffDetails ->
                DeliveryReducer.transitionTo(state, input, isRecovery = false)

            is ScreenInfo.PickupDetails ->
                PickupReducer.transitionTo(state, input, isRecovery = false)

            else -> null
        }

        if (screenCheck != null) {
            return screenCheck
        }

        return when (state.phase) {
            Phase.STABILIZING -> StabilizingPhase.reduce(state, input)
            Phase.CLICKING -> ClickingPhase.reduce(state, input)
            Phase.VERIFYING -> VerifyingPhase.reduce(state, input)
            Phase.RECORDED -> RecordedPhase.reduce(state, input)
        }
    }

    // --- TIMEOUT ROUTER ---

    fun onTimeout(state: AppStateV2.PostDelivery, event: TimeoutEvent): Reducer.Transition {
        val expected = when (state.phase) {
            Phase.STABILIZING -> StabilizingPhase.onTimeout(state, event)
            Phase.VERIFYING -> VerifyingPhase.onTimeout(state, event)
            else -> null
        }
        return expected ?: Reducer.Transition(state)
    }
}