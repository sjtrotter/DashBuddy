package cloud.trotter.dashbuddy.state.reducers.postdelivery

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.state.reducers.AwaitingReducer
import cloud.trotter.dashbuddy.state.reducers.DashPausedReducer
import cloud.trotter.dashbuddy.state.reducers.IdleReducer
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import cloud.trotter.dashbuddy.state.reducers.SummaryReducer
import cloud.trotter.dashbuddy.util.UtilityFunctions

internal object RecordedPhase {

    fun transitionTo(state: AppStateV2.PostDelivery): Reducer.Transition {
        val newState = state.copy(phase = AppStateV2.PostDelivery.Phase.RECORDED)

        val effects = mutableListOf<AppEffect>()
        val pay = state.totalPay

        // 1. Log Event
        effects.add(
            AppEffect.LogEvent(
                ReducerUtils.createEvent(
                    dashId = state.dashId,
                    type = AppEventType.DELIVERY_COMPLETED,
                    // Note: Ideally pass the full ParsedPay object here if you cached it.
                    // For now, logging the total.
                    payload = mapOf("total" to pay)
                )
            )
        )

        // 2. Screenshot with Custom Filename
        // Note: You need access to the merchant names here.
        // Ideally, 'AppStateV2.PostDelivery' should store 'merchants: String'
        // populated during VerifyingPhase.
        // For now, I'll use a generic fallback since we don't have the Input here.
        val filename = "Dropoff - ${UtilityFunctions.formatCurrency(pay)}"

        effects.add(AppEffect.CaptureScreenshot(filename))

        // 3. Bubble Update
        effects.add(AppEffect.UpdateBubble("Saved! ${UtilityFunctions.formatCurrency(pay)}"))

        return Reducer.Transition(newState, effects)
    }

    fun reduce(state: AppStateV2.PostDelivery, input: ScreenInfo): Reducer.Transition? {
        // Standard Exits
        return when (input) {
            is ScreenInfo.WaitingForOffer -> AwaitingReducer.transitionTo(state, input, false)
            is ScreenInfo.IdleMap -> IdleReducer.transitionTo(state, input, false)
            is ScreenInfo.DashSummary -> SummaryReducer.transitionTo(state, input, false)
            is ScreenInfo.DashPaused -> DashPausedReducer.transitionTo(state, input, false)
            else -> null
        }
    }
}