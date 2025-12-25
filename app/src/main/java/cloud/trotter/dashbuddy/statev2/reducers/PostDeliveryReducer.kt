package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer
import cloud.trotter.dashbuddy.util.UtilityFunctions

object PostDeliveryReducer {

    // --- FACTORY ---
    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.DeliveryCompleted,
        isRecovery: Boolean
    ): Reducer.Transition {
        val total = input.parsedPay.total

        if (oldState is AppStateV2.PostDelivery && oldState.totalPay == total) {
            return Reducer.Transition(oldState)
        }

        val newState = AppStateV2.PostDelivery(
            dashId = oldState.dashId,
            totalPay = total,
            summaryText = "Paid: ${UtilityFunctions.formatCurrency(total)}"
        )

        val effects = mutableListOf<AppEffect>()

        effects.add(
            AppEffect.LogEvent(
                ReducerUtils.createEvent(
                    dashId = oldState.dashId,
                    type = AppEventType.DELIVERY_COMPLETED,
                    payload = input.parsedPay
                )
            )
        )

        if (input.expandButton != null) {
            // Return the transition with the Click Effect
            effects.add(AppEffect.ClickNode(input.expandButton, "Expand Delivery Details"))
        }

        // --- Custom Filename ---
        // Format: 2025-12-20 - Dropoff - McDonald's (1234), Pizza Hut

        // 1. Get names from customerTips (e.g. "McDonald's (1234)")
        val merchants = input.parsedPay.customerTips
            .map { it.type.trim() }
            .distinct()
            .joinToString(", ")
            .ifEmpty { "Delivery" }

        // 2. Sanitize: Allow letters, numbers, spaces, parens, hyphens, commas, apostrophes.
        // Strip mostly just illegal file chars like / \ : * ? " < > |
        val safeMerchants = merchants.replace(Regex("[^a-zA-Z0-9 ,.()'-]"), "")

        val filename = "Dropoff - $safeMerchants"

        effects.add(AppEffect.CaptureScreenshot(filename))
        // -----------------------

        effects.add(AppEffect.UpdateBubble("Saved! ${UtilityFunctions.formatCurrency(total)}"))

        return Reducer.Transition(newState, effects)
    }

    // --- REDUCER ---
    fun reduce(
        state: AppStateV2.PostDelivery,
        input: ScreenInfo
    ): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.DeliveryCompleted -> Reducer.Transition(state)
            is ScreenInfo.WaitingForOffer -> AwaitingReducer.transitionTo(
                state,
                input,
                isRecovery = false
            )

            is ScreenInfo.IdleMap -> IdleReducer.transitionTo(state, input, isRecovery = false)
            is ScreenInfo.DashSummary -> SummaryReducer.transitionTo(
                state,
                input,
                isRecovery = false
            )

            is ScreenInfo.DashPaused -> DashPausedReducer.transitionTo(
                state,
                input,
                isRecovery = false
            )

            else -> null
        }
    }
}