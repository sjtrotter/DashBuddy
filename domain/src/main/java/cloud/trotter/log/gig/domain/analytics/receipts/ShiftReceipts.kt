package cloud.trotter.log.gig.domain.analytics.receipts

import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole

enum class ReceiptStatus {
    /** Unproven, not matched, and its own delivery is missing or still open: the dasher is asked. */
    OPEN,

    /** An active match names it. */
    MATCHED,

    /** Proven, or its own delivery already carries receipt money, a match or the dasher's pay: nothing to ask. */
    SETTLED,
}

data class ShiftReceipt(
    val receipt: SavedReceipt,
    val status: ReceiptStatus,
    /** The active match naming this receipt; null unless [status] is MATCHED. */
    val matchCommandId: String?,
    /** True while every row that match wrote still holds it ([ReceiptMatch.holdsWritten]). */
    val undoable: Boolean,
)

/**
 * One shift's saved receipts judged against its deliveries and active matches (#1599). Pure.
 *
 * A receipt whose own delivery is no longer open (it carries receipt money, a match or the dasher's pay) is SETTLED
 * and raises no check, so once one read of a receipt is matched across its job, a second read of the same receipt
 * never asks (the repeated-read rule). Residual: on a job with two or more offers the suggestion is the subject
 * alone, so two reads of one offer's receipt confirmed on that offer's two drops separately count it twice; the money
 * check shows it and Undo takes it back.
 */
data class ShiftReceipts(
    val items: List<ShiftReceipt>,
    val deliveries: List<DeliveryRecord>,
    val active: List<ActiveMatchRow>,
) {
    val open: List<ShiftReceipt> get() = items.filter { it.status == ReceiptStatus.OPEN }

    /** Delivery PK -> command id of the undoable active match that COVERS it (the job sheet's Undo match). */
    val undoByDelivery: Map<Long, String> by lazy {
        val undoable = items.filter { it.status == ReceiptStatus.MATCHED && it.undoable }.mapNotNull { it.matchCommandId }.toSet()
        active.filter { it.role == ReceiptMatchRole.COVERED && it.commandId in undoable }
            .associate { it.deliveryEventSequenceId to it.commandId }
    }

    fun totalCentsOf(evidenceId: String): Long? = items.firstOrNull { it.receipt.evidenceId == evidenceId }?.receipt?.totalCents

    companion object {
        val EMPTY = ShiftReceipts(emptyList(), emptyList(), emptyList())

        fun of(receipts: List<SavedReceipt>, deliveries: List<DeliveryRecord>, active: List<ActiveMatchRow>): ShiftReceipts {
            val byId = deliveries.associateBy { it.eventSequenceId }
            val items = receipts.sortedWith(compareBy({ it.firstSeenAt }, { it.evidenceId })).map { r ->
                val match = active.firstOrNull { it.evidenceId == r.evidenceId }
                when {
                    r.proven -> ShiftReceipt(r, ReceiptStatus.SETTLED, null, false)
                    match != null -> {
                        val rows = active.filter { it.commandId == match.commandId }
                        val undoable = rows.all { ReceiptMatch.holdsWritten(it, byId[it.deliveryEventSequenceId]) }
                        ShiftReceipt(r, ReceiptStatus.MATCHED, match.commandId, undoable)
                    }
                    else -> {
                        val subject = ReceiptMatch.subjectOf(r, deliveries)
                        val open = subject == null || ReceiptMatch.selectable(subject, active)
                        ShiftReceipt(r, if (open) ReceiptStatus.OPEN else ReceiptStatus.SETTLED, null, false)
                    }
                }
            }
            return ShiftReceipts(items, deliveries, active)
        }
    }
}
