package cloud.trotter.dashbuddy.state.reducers.postdelivery

import cloud.trotter.dashbuddy.pipeline.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition

internal object ClickingPhase {

    fun transitionTo(state: AppStateV2.PostDelivery): Transition {
        // Just switch the sign on the door. No effects yet.
        return Transition(
            state.copy(phase = AppStateV2.PostDelivery.Phase.CLICKING)
        )
    }

    fun reduce(state: AppStateV2.PostDelivery, input: ScreenInfo): Transition? {
        return when (input) {
            // THE HUNT IS SUCCESSFUL: We found the button
            is ScreenInfo.DeliverySummaryCollapsed -> {
                val clickEffect = AppEffect.ClickNode(
                    node = input.expandButton,
                    description = "Expand Details"
                )

                // Move to Verifying immediately.
                // We pass 'clickSent=true' so it waits 2.0s for animation.
                val transition = VerifyingPhase.transitionTo(state, clickSent = true)

                return transition.copy(
                    effects = listOf(clickEffect) + transition.effects
                )
            }

            // Missed it? (User clicked manually)
            is ScreenInfo.DeliveryCompleted -> {
                VerifyingPhase.transitionTo(state, input, clickSent = false)
            }

            else -> null
        }
    }
}