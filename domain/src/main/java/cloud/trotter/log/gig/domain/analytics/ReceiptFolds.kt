package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptObservedPayload

/**
 * One receipt evidence record (#1598) as produced by the pure fold — mapped 1:1 onto `ReceiptRecordEntity`. Each
 * `RECEIPT_OBSERVED` revision carries the whole record, and events fold in sequence order, so the latest revision
 * wins by [evidenceId].
 */
data class ReceiptFold(
    val evidenceId: String,
    val sessionId: String?,
    val platform: String,
    val jobId: String,
    val subjectTaskId: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val totalCents: Long,
    val basePayCents: Long?,
    val tipCents: Long?,
    val isExpanded: Boolean,
    /** `ReceiptScope.name`. */
    val scope: String,
    /** `ReceiptProofGap.name`, null when proven. */
    val proofGap: String?,
    val revision: Int,
    val lastEventSequenceId: Long,
)

/** The `RECEIPT_OBSERVED` fold (#1598). */
internal object ReceiptFolds {

    /**
     * Evidence only: it moves no money and is bookkeeping (`RecordFolds.BOOKKEEPING_EVENTS`), so the session
     * context passes through UNTOUCHED — no liveness advance, no odometer measurement. With or without these
     * events, every other row folds byte-identically.
     */
    fun foldReceiptObserved(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? ReceiptObservedPayload
            ?: return FoldOutcome(context = context, skip = "RECEIPT_OBSERVED: missing/malformed payload")
        return FoldOutcome(
            context = context,
            receipt = ReceiptFold(
                evidenceId = p.evidenceId,
                sessionId = e.sessionId,
                platform = p.platform,
                jobId = p.jobId,
                subjectTaskId = p.subjectTaskId,
                firstSeenAt = p.firstSeenAt,
                lastSeenAt = p.observedAt,
                totalCents = Formats.cents(p.totalPay),
                basePayCents = p.parsedPay?.let { Formats.cents(it.totalBasePay) },
                tipCents = p.parsedPay?.let { Formats.cents(it.totalTip) },
                isExpanded = p.isExpanded,
                scope = p.scope.name,
                proofGap = p.proofGap?.name,
                revision = p.revision,
                lastEventSequenceId = event.sequenceId,
            ),
        )
    }
}
