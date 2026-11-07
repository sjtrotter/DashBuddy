package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.ManualDeliveryRemovedPayload
import cloud.trotter.log.gig.domain.model.event.payload.TaskUnassignedPayload

/**
 * A recorded unassign's stamp decision: the projector sets `offer_records.unassignedAt` to [unassignedAt] on the
 * shift's ACCEPTED offers whose hash is in [offerHashes], sequenced before [beforeSequenceId], and not yet stamped
 * (the first unassign of a job wins). Applied by PK range inside the batch transaction, in event order.
 */
data class OfferUnassignFold(
    val sessionId: String,
    val offerHashes: List<String>,
    val unassignedAt: Long,
    /** The `TASK_UNASSIGNED` event's own sequence id: only offers before it are stamped, so a refold matches. */
    val beforeSequenceId: Long,
)

/**
 * A missed delivery added from an accepted offer: the projector stores [offerSequenceId] on the delivery row
 * [deliverySequenceId] (`delivery_records.sourceOfferSeq`) once its guards pass, and never touches the offer row.
 */
data class ManualSourceLinkFold(
    val deliverySequenceId: Long,
    val sessionId: String,
    val offerSequenceId: Long,
)

/**
 * A hand-added delivery's removal: the projector deletes the row [targetEventSequenceId]. [inShift] is true when the
 * shift's fold context still held the delivery, so the fold has already lowered the shift's counts; false is a
 * refusal (a machine row, another shift, a missing or already removed row), applied as a counted skip.
 */
data class ManualDeliveryRemoveFold(
    val targetEventSequenceId: Long,
    val sessionId: String,
    val inShift: Boolean,
)

/** The offer-repair decisions' pure builders. */
internal object OfferRepairFolds {

    /** The stamp for a `TASK_UNASSIGNED` that names its job's offers, else null (no session or no hashes). */
    fun unassign(event: SequencedAppEvent): OfferUnassignFold? {
        val p = event.event.payload as? TaskUnassignedPayload ?: return null
        val sid = event.event.sessionId ?: return null
        val hashes = p.jobOfferHashes.filter { it.isNotBlank() }.distinct()
        if (hashes.isEmpty()) return null
        return OfferUnassignFold(sid, hashes, p.unassignedAt, event.sequenceId)
    }

    /**
     * `MANUAL_DELIVERY_REMOVED`: when [context] is the payload's shift and still holds the hand-added delivery's job,
     * the delivery leaves the shift's counts here (deliveries and the delivered-job set, so jobs completed follows),
     * the same in an incremental fold, which hydrates the set from the rows, and in a refold. Otherwise no context is
     * returned, so the session row is not rewritten, and the decision is a refusal.
     */
    fun removeManual(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val p = event.event.payload as? ManualDeliveryRemovedPayload
            ?: return FoldOutcome(context = null, skip = "MANUAL_DELIVERY_REMOVED: missing/malformed payload")
        val jobId = MANUAL_JOB_PREFIX + p.targetEventSequenceId
        val ctx = context?.takeIf { it.sessionId == p.sessionId && jobId in it.deliveredJobIds }
        val fold = ManualDeliveryRemoveFold(p.targetEventSequenceId, p.sessionId, inShift = ctx != null)
        if (ctx == null) return FoldOutcome(context = null, manualDeliveryRemove = fold)
        val lowered = ctx.copy(deliveries = (ctx.deliveries - 1).coerceAtLeast(0), deliveredJobIds = ctx.deliveredJobIds - jobId)
        return FoldOutcome(context = lowered, manualDeliveryRemove = fold)
    }
}
