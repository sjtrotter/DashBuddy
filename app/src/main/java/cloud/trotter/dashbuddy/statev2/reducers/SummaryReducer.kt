package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.reducers.MainReducer.Transition
import java.util.UUID

object SummaryReducer {

    fun checkAnchors(state: AppStateV2, input: ScreenInfo, context: StateContext): Transition? {
        return when (input) {
            is ScreenInfo.DeliveryCompleted -> reduceDeliveryCompleted(state, input, context)
            is ScreenInfo.DashSummary -> reduceDashSummary(state, input, context)
            else -> null
        }
    }

    // FIX: If we have an ID, use it. If lost (crash), generate a NEW UUID.
    private fun resolveDashId(state: AppStateV2): String {
        return state.dashId ?: UUID.randomUUID().toString()
    }

    private fun reduceDeliveryCompleted(
        state: AppStateV2,
        input: ScreenInfo.DeliveryCompleted,
        context: StateContext
    ): Transition {
        val total = input.parsedPay.total
        if (state is AppStateV2.PostDelivery && state.totalPay == total) return Transition(state)

        val activeDashId = resolveDashId(state)
        val newState = AppStateV2.PostDelivery(
            dashId = activeDashId,
            totalPay = total,
            summaryText = "Paid: $$total"
        )

        val effects = listOf(
            AppEffect.LogEvent(
                ReducerUtils.createEvent(
                    activeDashId,
                    AppEventType.DELIVERY_COMPLETED,
                    ReducerUtils.gson.toJson(input.parsedPay),
                    context.odometerReading
                )
            ),
            AppEffect.CaptureScreenshot("payout_${total}_${System.currentTimeMillis()}"),
            AppEffect.UpdateBubble("Saved! $$total")
        )
        return Transition(newState, effects)
    }

    private fun reduceDashSummary(
        state: AppStateV2,
        input: ScreenInfo.DashSummary,
        context: StateContext
    ): Transition {
        if (state is AppStateV2.PostDash && state.totalEarnings == input.totalEarnings) return Transition(
            state
        )

        val activeDashId = resolveDashId(state)
        val newState = AppStateV2.PostDash(
            dashId = activeDashId,
            totalEarnings = input.totalEarnings ?: 0.0,
            durationMillis = input.onlineDurationMillis,
            acceptanceRateForSession = "${input.offersAccepted}/${input.offersTotal}"
        )

        val effects = listOf(
            AppEffect.LogEvent(
                ReducerUtils.createEvent(
                    activeDashId,
                    AppEventType.DASH_STOP,
                    ReducerUtils.gson.toJson(input),
                    context.odometerReading
                )
            ),
            AppEffect.UpdateBubble("Dash Ended. Total: $${input.totalEarnings ?: "?"}"),
            AppEffect.CaptureScreenshot("dash_summary_${System.currentTimeMillis()}")
        )
        return Transition(newState, effects)
    }

    // Exit Strategy (Clicking Done)
    fun reducePostState(state: AppStateV2, input: ScreenInfo): Transition {
        return if (input is ScreenInfo.IdleMap) {
            Transition(
                AppStateV2.IdleOffline(
                    lastKnownZone = input.zoneName,
                    dashType = input.dashType
                )
            )
        } else {
            Transition(state)
        }
    }
}