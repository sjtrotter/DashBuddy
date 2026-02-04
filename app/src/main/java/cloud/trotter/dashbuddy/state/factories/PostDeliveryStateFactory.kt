package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.data.pay.ParsedPay
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.TimeoutType
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostDeliveryStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo,
        isRecovery: Boolean
    ): Transition {
        return when (input) {
            is ScreenInfo.DeliverySummaryCollapsed -> createStabilizingEntry(oldState)
            is ScreenInfo.DeliveryCompleted -> createVerifyingEntry(
                oldState,
                input,
                clickSent = false
            )

            else -> {
                Timber.e("Invalid entry to PostDelivery: ${input::class.simpleName}")
                Transition(oldState)
            }
        }
    }

    // Extracted from StabilizingPhase.transitionTo
    private fun createStabilizingEntry(oldState: AppStateV2): Transition {
        val newState = AppStateV2.PostDelivery(
            dashId = oldState.dashId,
            phase = AppStateV2.PostDelivery.Phase.STABILIZING
        )
        return Transition(
            newState,
            listOf(AppEffect.ScheduleTimeout(500, TimeoutType.EXPAND_STABILITY))
        )
    }

    // Extracted from VerifyingPhase.transitionTo
    private fun createVerifyingEntry(
        oldState: AppStateV2,
        input: ScreenInfo.DeliveryCompleted,
        clickSent: Boolean
    ): Transition {
        val merchants = extractMerchants(input.parsedPay)

        val newState = AppStateV2.PostDelivery(
            dashId = oldState.dashId,
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

    private fun extractMerchants(parsedPay: ParsedPay): String {
        return parsedPay.customerTips.joinToString(", ") { it.type.trim() }
            .ifEmpty { "Delivery" }
            .replace(Regex("[^a-zA-Z0-9 ,.()'-]"), "")
    }
}