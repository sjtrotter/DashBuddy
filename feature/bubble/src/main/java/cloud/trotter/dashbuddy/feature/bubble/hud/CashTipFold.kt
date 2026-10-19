package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryAdjustmentPayload

data class CashTipEntry(val deliverySeq: Long, val jobId: String, val taskId: String, val amount: Double, val at: Long)

object CashTipFold {
    fun fold(events: List<AppEvent>, deliveries: List<DeliveryRecord>): List<CashTipEntry> {
        val times = events.filter { it.type == AppEventType.DELIVERY_ADJUSTMENT }
            .mapNotNull { event -> (event.payload as? DeliveryAdjustmentPayload)
                ?.takeIf { it.newCashTip != null }?.let { it.targetEventSequenceId to event.occurredAt } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.max() }
        return deliveries.distinctBy { it.eventSequenceId }.mapNotNull { row ->
            row.cashTip?.takeIf { it > 0 }?.let { amount ->
                CashTipEntry(row.eventSequenceId, row.jobId, row.taskId, amount,
                    times[row.eventSequenceId] ?: row.completedAt)
            }
        }
    }
}
