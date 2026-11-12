package cloud.trotter.log.gig.domain.analytics.timeline

import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope

/**
 * The read-model rows [ShiftTimeline.fold] reads, one shift's worth each (#1516). The repository maps
 * `offer_records`, `pickup_records` and `delivery_records` onto these; no estimate or score is carried.
 */

/** One closed offer of the shift, any outcome. */
data class TimelineOffer(
    val eventSequenceId: Long,
    val offerHash: String,
    /** Null for a stored outcome this build does not know; the fold skips such a row. */
    val outcome: OfferOutcome?,
    val decidedAt: Long,
    val payAmount: Double?,
    val distanceMiles: Double?,
    /** The store text as recorded (`offer_records.merchantName`, "A & B" for a stack). */
    val merchantName: String?,
    /** Null on a row folded before projector 18. */
    val offerScope: OfferScope?,
    /** Null on a row folded before projector 18. */
    val quoteBasis: OfferQuoteBasis?,
    /** Store resolution's offer-to-job stamp; the fold's second join. */
    val linkedJobId: String?,
    /** `offer_records.outcomeResolved`: an `OfferOutcomeResolution` value, null when not resolved. */
    val outcomeResolved: String? = null,
    /** When a recorded unassign named this offer's job (`offer_records.unassignedAt`); null when none. */
    val unassignedAt: Long? = null,
    /** #1119 S2: the jobs this offer tied on at its store (`offer_records.ambiguousJobIds`); empty when none. */
    val ambiguousJobIds: List<String> = emptyList(),
)

/** One confirmed pickup of the shift. */
data class TimelinePickup(
    val eventSequenceId: Long,
    val jobId: String,
    val storeName: String,
    val arrivedAt: Long?,
    val confirmedAt: Long?,
    /** The job's offer hashes at confirmation; null on a row folded before projector 18 or with none. */
    val jobOfferHashes: List<String>?,
)

/** One delivery of the shift. */
data class TimelineDelivery(
    val eventSequenceId: Long,
    val jobId: String,
    val storeName: String?,
    /** When the dasher arrived at the customer; null when not recorded. */
    val arrivedAt: Long?,
    val completedAt: Long,
    val realizedPay: Double?,
    /** `delivery_records.payBasis` ([cloud.trotter.log.gig.domain.analytics.PayBasis]). */
    val payBasis: String,
    val cashTip: Double?,
    /** The job's offer hashes; null on a row folded before projector 18. */
    val jobOfferHashes: List<String>?,
    /** The job's one offer hash, when it had exactly one (the pre-18 rows' only hash evidence). */
    val soleOfferHash: String?,
    /** The accepted offer a missed delivery was added from (`delivery_records.sourceOfferSeq`); null otherwise. */
    val sourceOfferSeq: Long? = null,
) {
    /** The hashes this row names: the full set, else the sole hash on an older row. */
    val offerHashes: List<String> get() = jobOfferHashes ?: listOfNotNull(soleOfferHash)
}
