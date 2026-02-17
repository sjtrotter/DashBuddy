package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.data.pay.ParsedPay
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.util.UtilityFunctions
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostDeliveryStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo,
        isRecovery: Boolean
    ): Transition {
        // Initialize clean state
        val baseState = AppStateV2.PostDelivery(
            dashId = oldState.dashId
        )

        // Pre-populate if we have data immediately
        return when (input) {
            is ScreenInfo.DeliverySummary -> {
                val pay = input.parsedPay
                if (pay != null) {
                    val merchants = extractMerchants(pay)
                    Transition(
                        baseState.copy(
                            parsedPay = pay,
                            totalPay = pay.total,
                            merchantNames = merchants,
                            summaryText = "Paid: ${UtilityFunctions.formatCurrency(pay.total)}"
                        )
                    )
                } else {
                    // We only have the header total
                    val total = input.totalPay
                    Transition(
                        baseState.copy(
                            totalPay = total,
                            summaryText = if (total > 0) "Paid: ${
                                UtilityFunctions.formatCurrency(
                                    total
                                )
                            }" else "Processing..."
                        )
                    )
                }
            }

            else -> Transition(baseState)
        }
    }

    private fun extractMerchants(parsedPay: ParsedPay): String {
        return parsedPay.customerTips.joinToString(", ") { it.type.trim() }
            .ifEmpty { "Delivery" }
            .replace(Regex("[^a-zA-Z0-9 ,.()'-]"), "")
    }
}