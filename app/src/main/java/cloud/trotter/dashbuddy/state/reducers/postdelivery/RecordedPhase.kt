package cloud.trotter.dashbuddy.state.reducers.postdelivery

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import cloud.trotter.dashbuddy.util.UtilityFunctions

internal object RecordedPhase {

    fun transitionTo(state: AppStateV2.PostDelivery): Reducer.Transition {
        val newState = state.copy(phase = AppStateV2.PostDelivery.Phase.RECORDED)

        val effects = mutableListOf<AppEffect>()

        // 1. Data Snapshot
        val payData = state.parsedPay
        val total = state.totalPay
        val merchants = state.merchantNames

        // 2. Log Event (Save the full object to DB)
        // This keeps your database clean and queryable later
        effects.add(
            AppEffect.LogEvent(
                ReducerUtils.createEvent(
                    dashId = state.dashId,
                    type = AppEventType.DELIVERY_COMPLETED,
                    payload = payData ?: mapOf("total" to total, "error" to "Partial Data")
                )
            )
        )

        // 3. Screenshot
        val filename = "Dropoff - $merchants"
        effects.add(AppEffect.CaptureScreenshot(filename))

        // 4. Bubble Update (The "Receipt")
        val bubbleMessage = if (payData != null) {
            generateReceiptText(payData, total)
        } else {
            "Saved: ${UtilityFunctions.formatCurrency(total)}\n(No breakdown found)"
        }

        effects.add(AppEffect.UpdateBubble(bubbleMessage, ChatPersona.Earnings))

        return Reducer.Transition(newState, effects)
    }

    private fun generateReceiptText(
        data: cloud.trotter.dashbuddy.data.pay.ParsedPay,
        total: Double
    ): String {
        val sb = StringBuilder()

        // Header
        sb.append("Saved: ${UtilityFunctions.formatCurrency(total)}")

        // App Pay Section
        // e.g. "Base Pay • $2.50"
        data.appPayComponents.forEach { item ->
            sb.append("\n${item.type} • ${UtilityFunctions.formatCurrency(item.amount)}")
        }

        // Tips Section
        // e.g. "McDonald's • $4.00"
        // (The 'type' field in Tips usually contains the Merchant Name from the parser)
        data.customerTips.forEach { item ->
            sb.append("\nTip: ${item.type} • ${UtilityFunctions.formatCurrency(item.amount)}")
        }

        return sb.toString()
    }

    fun reduce(state: AppStateV2.PostDelivery, input: ScreenInfo): Reducer.Transition? {
        return null //let main reducer take care of this...?
    }
}