package cloud.trotter.dashbuddy.state.reducers.postdelivery

import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.model.TimeoutType

internal object StabilizingPhase {

    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.DeliverySummaryCollapsed
    ): Reducer.Transition {
        val newState = AppStateV2.PostDelivery(
            dashId = oldState.dashId,
            phase = AppStateV2.PostDelivery.Phase.STABILIZING
        )
        // Wait 1.5s to ensure screen isn't flickering
        return Reducer.Transition(
            newState,
            listOf(AppEffect.ScheduleTimeout(1500, TimeoutType.EXPAND_STABILITY))
        )
    }

    fun reduce(state: AppStateV2.PostDelivery, input: ScreenInfo): Reducer.Transition? {
        // Optimization: If user clicks it manually while we wait
        if (input is ScreenInfo.DeliveryCompleted) {
            return VerifyingPhase.transitionTo(state, input, clickSent = false)
        }
        return null
    }

    fun onTimeout(state: AppStateV2.PostDelivery, event: TimeoutEvent): Reducer.Transition? {
        if (event.type == TimeoutType.EXPAND_STABILITY) {
            // Timer Done -> Move to Hunter Mode
            return ClickingPhase.transitionTo(state)
        }
        return null
    }
}