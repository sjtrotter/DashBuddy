package cloud.trotter.dashbuddy.state.reducers.postdelivery

import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.model.TimeoutType
import cloud.trotter.dashbuddy.state.model.Transition // <--- Updated Import

internal object StabilizingPhase {

    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.DeliverySummaryCollapsed
    ): Transition {
        val newState = AppStateV2.PostDelivery(
            dashId = oldState.dashId,
            phase = AppStateV2.PostDelivery.Phase.STABILIZING
        )
        // Wait .5s to ensure screen isn't flickering
        return Transition(
            newState,
            listOf(AppEffect.ScheduleTimeout(500, TimeoutType.EXPAND_STABILITY))
        )
    }

    fun reduce(state: AppStateV2.PostDelivery, input: ScreenInfo): Transition? {
        // Optimization: If user clicks it manually while we wait
        if (input is ScreenInfo.DeliveryCompleted) {
            return VerifyingPhase.transitionTo(state, input, clickSent = false)
        }
        return null
    }

    fun onTimeout(state: AppStateV2.PostDelivery, event: TimeoutEvent): Transition? {
        if (event.type == TimeoutType.EXPAND_STABILITY) {
            // Timer Done -> Move to Hunter Mode
            return ClickingPhase.transitionTo(state)
        }
        return null
    }
}