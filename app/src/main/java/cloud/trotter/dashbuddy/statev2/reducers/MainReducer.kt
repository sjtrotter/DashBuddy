package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2

object MainReducer {

    data class Transition(
        val newState: AppStateV2,
        val effects: List<AppEffect> = emptyList()
    )

    fun reduce(currentState: AppStateV2, context: StateContext): Transition {
        val input = context.screenInfo ?: return Transition(currentState)
        if (input.screen == Screen.UNKNOWN) return Transition(currentState)

        // 1. STANDARD ROUTE: Try the specific reducer for the current state.
        val primaryTransition = when (currentState) {
            is AppStateV2.Initializing -> InitializingReducer.reduce(currentState, input)
            is AppStateV2.IdleOffline -> IdleReducer.reduce(currentState, input, context)
            is AppStateV2.AwaitingOffer -> AwaitingReducer.reduce(currentState, input, context)
            is AppStateV2.OfferPresented -> OfferReducer.reduce(currentState, input, context)
            is AppStateV2.OnPickup -> PickupReducer.reduce(currentState, input, context)

            // Post-States (Summary/Delivery) usually just wait for "Done" click
            is AppStateV2.PostDash, is AppStateV2.PostDelivery -> SummaryReducer.reducePostState(
                currentState,
                input
            )

            else -> Transition(currentState)
        }

        // 2. FALLBACK ANCHORS: If standard route did nothing, check if we need to re-orient.
        // (Self-Healing logic for Crashes or Missed Transitions)
        if (primaryTransition.newState == currentState && primaryTransition.effects.isEmpty()) {
            val anchor = SummaryReducer.checkAnchors(currentState, input, context)
            if (anchor != null) {
                return anchor
            }
        }

        return primaryTransition
    }
}