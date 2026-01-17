package cloud.trotter.dashbuddy.state.reducers.postdelivery

import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.model.TimeoutType
import cloud.trotter.dashbuddy.util.UtilityFunctions

internal object VerifyingPhase {

    // Entry 1: From Clicking (We don't know the pay yet)
    fun transitionTo(
        oldState: AppStateV2.PostDelivery,
        clickSent: Boolean
    ): Reducer.Transition {
        val newState = oldState.copy(
            phase = AppStateV2.PostDelivery.Phase.VERIFYING,
            summaryText = "Verifying..."
        )
        // If we clicked, allow 2s for animation.
        val waitTime = if (clickSent) 2000L else 1000L

        return Reducer.Transition(
            newState,
            listOf(AppEffect.ScheduleTimeout(waitTime, TimeoutType.VERIFY_PAY))
        )
    }

    // Entry 2: From Manual Detection (We know the pay)
    fun transitionTo(
        oldState: AppStateV2.PostDelivery,
        input: ScreenInfo.DeliveryCompleted,
        clickSent: Boolean
    ): Reducer.Transition {
        val newState = oldState.copy(
            phase = AppStateV2.PostDelivery.Phase.VERIFYING,
            totalPay = input.parsedPay.total,
            summaryText = "Paid: ${UtilityFunctions.formatCurrency(input.parsedPay.total)}"
        )
        val waitTime = if (clickSent) 2000L else 1000L

        return Reducer.Transition(
            newState,
            listOf(AppEffect.ScheduleTimeout(waitTime, TimeoutType.VERIFY_PAY))
        )
    }

    fun reduce(state: AppStateV2.PostDelivery, input: ScreenInfo): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.DeliveryCompleted -> {
                // If numbers changed (e.g. tip appeared), RESET timer!
                if (input.parsedPay.total != state.totalPay) {
                    transitionTo(state, input, clickSent = false)
                } else null
            }
            // Ignore "Collapsed" screens (animation artifacts)
            is ScreenInfo.DeliverySummaryCollapsed -> null
            else -> null
        }
    }

    fun onTimeout(state: AppStateV2.PostDelivery, event: TimeoutEvent): Reducer.Transition? {
        if (event.type == TimeoutType.VERIFY_PAY) {
            // Timer Done -> SAVE IT.
            // Note: We need to grab the last known pay.
            // If we came from Clicking and never saw 'DeliveryCompleted',
            // totalPay might be null. (Safety check needed in RecordedPhase).
            return RecordedPhase.transitionTo(state)
        }
        return null
    }
}