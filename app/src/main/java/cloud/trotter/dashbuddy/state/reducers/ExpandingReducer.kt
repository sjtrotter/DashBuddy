package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer

object ExpandingReducer {

    /**
     * Entry Point: Transitions to the expanding state and triggers the click IMMEDIATELY.
     * This ensures the click happens exactly once (on state entry).
     */
    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.DeliverySummaryCollapsed,
        isRecovery: Boolean
    ): Reducer.Transition {
        // 1. Create the Holding State
        val newState = AppStateV2.ExpandingDeliverySummary(
            dashId = oldState.dashId
        )

        // 2. Define the Entry Effect (The Click)
        // We do this here so it's tied to the transition, not the loop.
        val effects = mutableListOf<AppEffect>()
        if (!isRecovery) {
            effects.add(
                AppEffect.Delayed(
                    600,
                    AppEffect.ClickNode(input.expandButton, "Expand Delivery Details")
                )
            )
        }

        return Reducer.Transition(newState, effects)
    }

    /**
     * The Waiting Room: We are now in 'ExpandingDeliverySummary'.
     * We ignore the collapsed screen (because we already clicked it) and wait for the expanded one.
     */
    fun reduce(
        state: AppStateV2.ExpandingDeliverySummary,
        input: ScreenInfo
    ): Reducer.Transition? {
        return when (input) {
            // IGNORE: The screen is still collapsed (animation playing). Do nothing.
            is ScreenInfo.DeliverySummaryCollapsed -> Reducer.Transition(state)

            // SUCCESS: The screen expanded! Move to PostDelivery.
            is ScreenInfo.DeliveryCompleted -> {
                PostDeliveryReducer.transitionTo(state, input, isRecovery = false)
            }

            // SAFETY: Handling interruptions
            is ScreenInfo.WaitingForOffer -> AwaitingReducer.transitionTo(
                state,
                input,
                isRecovery = false
            )

            is ScreenInfo.DashPaused -> DashPausedReducer.transitionTo(
                state,
                input,
                isRecovery = false
            )

            // Stay in state for noise
            else -> Reducer.Transition(state)
        }
    }
}