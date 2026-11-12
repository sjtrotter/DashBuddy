package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchUndonePayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchedPayload

/** A dasher's receipt match (#1599) as the pure fold reads it; the projector applies it against the stored rows. */
data class ReceiptMatchFold(
    val commandId: String,
    val sessionId: String,
    val evidenceId: String,
    val receiptCents: Long,
    /** COVERED lines in payload order: delivery PK to cents. */
    val covered: List<Pair<Long, Long>>,
    /** The CLEARED delivery PKs the preview showed; the projector derives its own set and compares. */
    val previewCleared: Set<Long>,
    /** The event's `occurredAt`: the rows' `driverAdjustedAt`. */
    val matchedAt: Long,
    /** The event's own sequence id: the apply reads only delivery rows sequenced before it. */
    val sequenceId: Long,
)

/** A dasher's undo of the receipt match [matchCommandId] (#1599). */
data class ReceiptMatchUndoFold(val sessionId: String, val matchCommandId: String, val undoneAt: Long, val sequenceId: Long)

/**
 * The `RECEIPT_MATCHED` / `RECEIPT_MATCH_UNDONE` folds (#1599), the [CorrectionFolds.foldSessionReportCorrection]
 * shape: the target must be an ENDED shift in [SessionFoldContext] at the event's log position, so one batch or many
 * give the same answer, and the outcome carries no context (no liveness advance, no session re-upsert). Pure.
 */
internal object ReceiptMatchFolds {

    fun foldMatched(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? ReceiptMatchedPayload
            ?: return FoldOutcome(context = null, skip = "RECEIPT_MATCHED: missing/malformed payload")
        if (e.sessionId != p.sessionId || context == null || context.sessionId != p.sessionId || context.endedAt == null) {
            return FoldOutcome(context = null, skip = "RECEIPT_MATCHED: target shift unknown or not ended at this log position")
        }
        val covered = p.lines.filter { it.role == ReceiptMatchRole.COVERED }
        val cleared = p.lines.filter { it.role == ReceiptMatchRole.CLEARED }
        val malformed = p.commandId.isBlank() || p.receiptCents <= 0 || covered.isEmpty() ||
            p.lines.map { it.deliveryEventSequenceId }.distinct().size != p.lines.size ||
            covered.any { (it.newPayCents ?: -1) < 0 } || cleared.any { it.newPayCents != null } ||
            covered.sumOf { it.newPayCents ?: 0 } != p.receiptCents
        if (malformed) return FoldOutcome(context = null, skip = "RECEIPT_MATCHED: malformed match")
        return FoldOutcome(
            context = null,
            receiptMatch = ReceiptMatchFold(
                commandId = p.commandId,
                sessionId = p.sessionId,
                evidenceId = p.evidenceId,
                receiptCents = p.receiptCents,
                covered = covered.map { it.deliveryEventSequenceId to it.newPayCents!! },
                previewCleared = cleared.map { it.deliveryEventSequenceId }.toSet(),
                matchedAt = e.occurredAt,
                sequenceId = event.sequenceId,
            ),
        )
    }

    fun foldUndone(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? ReceiptMatchUndonePayload
            ?: return FoldOutcome(context = null, skip = "RECEIPT_MATCH_UNDONE: missing/malformed payload")
        if (e.sessionId != p.sessionId || context == null || context.sessionId != p.sessionId || context.endedAt == null) {
            return FoldOutcome(context = null, skip = "RECEIPT_MATCH_UNDONE: target shift unknown or not ended at this log position")
        }
        if (p.matchCommandId.isBlank()) return FoldOutcome(context = null, skip = "RECEIPT_MATCH_UNDONE: malformed undo")
        return FoldOutcome(
            context = null,
            receiptMatchUndo = ReceiptMatchUndoFold(p.sessionId, p.matchCommandId, e.occurredAt, event.sequenceId),
        )
    }
}
