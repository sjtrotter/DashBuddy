package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.domain.analytics.ExpenseRecord
import cloud.trotter.log.gig.domain.analytics.FuelRecord
import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.state.PhaseColor
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle

/** S3 shares one window between its strip and the log, and routes both through onUndo. */
sealed interface UndoTarget {
    data class CashTip(val deliverySeq: Long?, val taskId: String) : UndoTarget
    data class Ledger(val eventSequenceId: Long) : UndoTarget
}
data class UndoWindow(val target: UndoTarget, val amount: Double, val closesAt: Long)
data class PendingCashTip(val jobId: String, val taskId: String, val amount: Double, val at: Long)

data class LogData(
    val events: List<AppEvent> = emptyList(),
    val deliveries: List<DeliveryRecord> = emptyList(),
    val expenses: List<ExpenseRecord> = emptyList(),
    val fuel: List<FuelRecord> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
) {
    operator fun plus(other: LogData) = LogData(events + other.events, deliveries + other.deliveries,
        expenses + other.expenses, fuel + other.fuel, (vehicles + other.vehicles).distinctBy { it.id })
    companion object { val Empty = LogData() }
}

sealed interface LogItem { val key: String; val at: Long }
data class JobCardModel(
    val jobId: String, val store: String, val score: Int?, val scoreTone: ScoreTone,
    val pay: Double?, val miles: Double?, val lines: List<JobLine>, val live: Boolean,
    override val at: Long,
) : LogItem { override val key get() = "job:$jobId" }
data class OfferRow(
    override val key: String, override val at: Long, val store: String?, val pay: Double?,
    val miles: Double?, val outcome: AppEventType,
) : LogItem { val dimmed get() = outcome != AppEventType.OFFER_ACCEPTED }
data class ExpenseRow(
    val sequenceId: Long, override val at: Long, val category: ExpenseCategory,
    val note: String?, val amount: Double, val vehicleName: String? = null,
) : LogItem { override val key get() = "expense:$sequenceId" }
data class FuelRow(val sequenceId: Long, override val at: Long, val total: Double, val pricePerGallon: Double?,
    val vehicleName: String? = null) : LogItem {
    override val key get() = "fuel:$sequenceId"
}
data class DispatcherRow(override val key: String, override val at: Long, val text: String) : LogItem

enum class ScoreTone { GOOD, WARN, BAD, NEUTRAL }
enum class NowPhase(val color: PhaseColor) {
    PICKUP_NAV(PhaseColor.PICKUP), PICKUP_ARRIVED(PhaseColor.PICKUP), SHOPPING(PhaseColor.PICKUP),
    DROPOFF_NAV(PhaseColor.DROPOFF), DROPOFF_ARRIVED(PhaseColor.DROPOFF), RECEIPT(PhaseColor.DROPOFF),
}
sealed interface JobLine {
    val at: Long
    data class Accepted(override val at: Long, val perHour: Double?, val perMile: Double?, val addOn: Boolean) : JobLine
    data class PickedUp(val arrivedAt: Long?, val pickedUpAt: Long, val dwellMs: Long?) : JobLine {
        override val at get() = pickedUpAt
    }
    data class Delivered(override val at: Long, val paid: Double?) : JobLine
    data class Unassigned(override val at: Long) : JobLine
    data class CashTip(override val at: Long, val amount: Double, val pending: Boolean, val undo: UndoWindow?) : JobLine
    data class Now(
        val phase: NowPhase, val anchorAt: Long, val dueAt: Long?, val net: Double?,
        val shopped: Int? = null, val total: Int? = null,
    ) : JobLine { override val at get() = anchorAt }
}
