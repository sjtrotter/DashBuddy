package cloud.trotter.dashbuddy.domain.analytics

/**
 * #810 B2 Tier 2 — the read model for the driver-attestation surface. A [OrphanOfferGroup] is one
 * still-open `JOB_ACCEPT_MISMATCH` (an accepted-offer-vs-delivery mismatch the projector's Tier-1
 * store-evidence join could not resolve) whose accepted offers the driver can attest one of as the
 * invisibly-unassigned one. No customer PII — store/pay/time are driver-recognizable facts (merchant
 * data), and no customer hashes ride these models (Principle 6/7).
 */
data class OrphanOfferGroup(
    /** The closing job that carried more accepted offers than delivered drops. */
    val jobId: String,
    /** The dash the mismatch belonged to (the `JOB_ACCEPT_MISMATCH` event's session). */
    val sessionId: String?,
    /** How many accepted offers are owed a resolution (`acceptedCount − accountedCount`). */
    val orphansOwed: Int,
    /** The job's accepted offers — the pick-from list (store/pay/time; resolved state carried). */
    val offers: List<OrphanOfferCandidate>,
)

/** One accepted offer the driver can attest as unassigned (or undo), within an [OrphanOfferGroup]. */
data class OrphanOfferCandidate(
    /** `offer_records.eventSequenceId` — the by-PK target of the `OFFER_OUTCOME_CORRECTION`. */
    val offerEventSequenceId: Long,
    /** The offer's parsed store (`offer_records.merchantName`), driver-recognizable. */
    val storeName: String?,
    /** The offer's parsed pay (`offer_records.payAmount`). */
    val payAmount: Double?,
    /** When the offer was decided (`offer_records.decidedAt`) — distinguishes same-store offers. */
    val decidedAt: Long,
    /** True once this offer has been resolved (Tier-1 inferred or Tier-2 attested) — undo target. */
    val resolved: Boolean,
)
