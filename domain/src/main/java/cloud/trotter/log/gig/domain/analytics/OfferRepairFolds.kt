package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
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

/** The two offer-repair decisions' pure builders. */
internal object OfferRepairFolds {

    /** The stamp for a `TASK_UNASSIGNED` that names its job's offers, else null (no session or no hashes). */
    fun unassign(event: SequencedAppEvent): OfferUnassignFold? {
        val p = event.event.payload as? TaskUnassignedPayload ?: return null
        val sid = event.event.sessionId ?: return null
        val hashes = p.jobOfferHashes.filter { it.isNotBlank() }.distinct()
        if (hashes.isEmpty()) return null
        return OfferUnassignFold(sid, hashes, p.unassignedAt, event.sequenceId)
    }
}
