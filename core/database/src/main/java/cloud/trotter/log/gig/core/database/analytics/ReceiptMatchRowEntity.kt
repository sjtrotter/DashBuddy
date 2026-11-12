package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Entity
import androidx.room.Index

/**
 * One delivery row a receipt match (#1599) wrote, with what it found there, so its undo restores it exactly.
 * Folded from `RECEIPT_MATCHED`; `undoneAt` stamped by `RECEIPT_MATCH_UNDONE`. A rebuildable projection: wiped and
 * refolded with the other read-model tables. App ids and money only; not in the CSV export.
 */
@Entity(
    tableName = "receipt_match_rows",
    primaryKeys = ["commandId", "deliveryEventSequenceId"],
    indices = [Index("sessionId"), Index("evidenceId"), Index("deliveryEventSequenceId")],
)
data class ReceiptMatchRowEntity(
    val commandId: String,
    val deliveryEventSequenceId: Long,
    val sessionId: String,
    val evidenceId: String,
    /** `ReceiptMatchRole.name`. */
    val role: String,
    val matchEventSequenceId: Long,
    val matchedAt: Long,
    val priorRealizedPay: Double?,
    val priorPayBasis: String,
    val priorNetProfit: Double?,
    val priorTip: Double?,
    val priorBasePay: Double?,
    val priorDriverAdjustedAt: Long?,
    /** COVERED: the share written; CLEARED: null. */
    val newPayCents: Long?,
    val undoneAt: Long? = null,
    val undoEventSequenceId: Long? = null,
)
