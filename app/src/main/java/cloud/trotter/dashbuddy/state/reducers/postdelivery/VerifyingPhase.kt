package cloud.trotter.dashbuddy.state.reducers.postdelivery

import cloud.trotter.dashbuddy.data.pay.ParsedPay
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.model.TimeoutType
import cloud.trotter.dashbuddy.state.model.Transition // <--- Updated Import
import cloud.trotter.dashbuddy.util.UtilityFunctions

internal object VerifyingPhase {

    // Helper to extract sanitized merchant names
    private fun extractMerchants(parsedPay: ParsedPay): String {
        return parsedPay.customerTips.joinToString(", ") { it.type.trim() }
            .ifEmpty { "Delivery" }
            .replace(Regex("[^a-zA-Z0-9 ,.()'-]"), "")
    }

    // Entry from CLICKING (No pay data yet)
    fun transitionTo(
        oldState: AppStateV2.PostDelivery,
        clickSent: Boolean
    ): Transition {
        val newState = oldState.copy(
            phase = AppStateV2.PostDelivery.Phase.VERIFYING,
            summaryText = "Verifying..."
        )
        // If we clicked, wait 1s for animation. If we didn't, .5s is enough.
        val waitTime = if (clickSent) 1000L else 500L

        return Transition(
            newState,
            listOf(AppEffect.ScheduleTimeout(waitTime, TimeoutType.VERIFY_PAY))
        )
    }

    // Entry from MANUAL DETECTION (Recovery)
    fun transitionTo(
        oldState: AppStateV2.PostDelivery,
        input: ScreenInfo.DeliveryCompleted,
        clickSent: Boolean
    ): Transition {
        val merchants = extractMerchants(input.parsedPay)

        val newState = oldState.copy(
            phase = AppStateV2.PostDelivery.Phase.VERIFYING,
            parsedPay = input.parsedPay,
            merchantNames = merchants,
            summaryText = "Paid: ${UtilityFunctions.formatCurrency(input.parsedPay.total)}"
        )

        val waitTime = if (clickSent) 2000L else 1000L
        return Transition(
            newState,
            listOf(AppEffect.ScheduleTimeout(waitTime, TimeoutType.VERIFY_PAY))
        )
    }

    fun reduce(state: AppStateV2.PostDelivery, input: ScreenInfo): Transition? {
        return when (input) {
            is ScreenInfo.DeliveryCompleted -> {
                // TRUST THE MATCHER: If we got this event, the data is valid.
                val newPay = input.parsedPay
                val currentTotal = newPay.total
                val storedTotal = state.parsedPay?.total

                // Only update if the total amount changed (e.g. tip popped in)
                if (currentTotal != storedTotal) {
                    val merchants = extractMerchants(newPay)

                    val newState = state.copy(
                        parsedPay = newPay,
                        merchantNames = merchants,
                        summaryText = "Paid: ${UtilityFunctions.formatCurrency(currentTotal)}"
                    )

                    // Reset timer to ensure stability
                    return Transition(
                        newState,
                        listOf(AppEffect.ScheduleTimeout(1000, TimeoutType.VERIFY_PAY))
                    )
                }
                null
            }

            is ScreenInfo.DeliverySummaryCollapsed -> null
            else -> null
        }
    }

    fun onTimeout(state: AppStateV2.PostDelivery, event: TimeoutEvent): Transition? {
        if (event.type == TimeoutType.VERIFY_PAY) {
            // Timer Done -> Move to record data
            return RecordedPhase.transitionTo(state)
        }
        return null
    }
}