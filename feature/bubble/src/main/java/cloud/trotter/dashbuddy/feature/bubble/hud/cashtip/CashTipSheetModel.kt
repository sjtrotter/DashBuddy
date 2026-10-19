package cloud.trotter.dashbuddy.feature.bubble.hud.cashtip

import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.format.Formats

enum class CashTipPage { COMPACT, EXPANDED, OTHER_AMOUNT }
enum class CashTipSign { PLUS, MINUS }
enum class CashTipFailure { NONE, WRITE, BELOW_ZERO }

data class CashTipSheetModel(
    val deliveries: List<DeliveryRecord>,
    val selectedDeliveryId: Long?,
    val totalCents: Long = 0,
    val sign: CashTipSign = CashTipSign.PLUS,
    val page: CashTipPage = CashTipPage.COMPACT,
    val otherAmount: String = "",
    val busy: Boolean = false,
    val failed: CashTipFailure = CashTipFailure.NONE,
    val pendingCashTipCents: Map<Long, Long> = emptyMap(),
) {
    val selected: DeliveryRecord? get() = deliveries.find { it.eventSequenceId == selectedDeliveryId }
    val canCommit: Boolean get() = selected != null && totalCents > 0 && !busy &&
        (sign == CashTipSign.PLUS || totalCents <= (pendingCashTipCents[selectedDeliveryId]
            ?: Formats.cents(selected?.cashTip ?: 0.0)))
    /** Keep the selected delivery visible when returning from the full picker. */
    val ordered: List<DeliveryRecord> get() = deliveries.sortedWith(
        compareByDescending<DeliveryRecord> { it.eventSequenceId == selectedDeliveryId }
            .thenByDescending { it.completedAt }.thenByDescending { it.eventSequenceId })
}
