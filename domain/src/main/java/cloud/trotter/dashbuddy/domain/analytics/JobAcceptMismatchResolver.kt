package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.state.StoreKeys

/**
 * #810 B2 Tier 1 — the pure store-evidence join that resolves an invisible-unassign orphan when the
 * surrounding screens make it unambiguous, and INCONCLUSIVE (null) otherwise.
 *
 * When a job closes carrying MORE accepted offers than delivered drops (`JOB_ACCEPT_MISMATCH`, B1), an
 * accepted offer silently vanished — but WHICH one is only knowable when the delivered drops' stores
 * distinguish it. The dev steer ("surrounding/resulting screens should clear that up, no?"): **yes for
 * cross-store, structurally no for same-store.** The resulting delivery screens tell you which offer
 * SURVIVED (its parsed store joins one delivered drop's store), so a cross-store orphan is
 * machine-resolvable; but the fielded seq-114 shape was two same-store (H-E-B) accepts with one
 * delivered H-E-B drop, and an offer carries no customer identity pre-accept, so nothing before or
 * after breaks that tie — it must fall to Tier-2 driver attestation.
 *
 * **The predicate (design point):** an accepted offer is "store-accounted" when its parsed store's
 * chain bucket ([StoreKeys.normalizedChain], the #159 SSOT) matches ANY delivered drop's store
 * evidence. Resolve the orphan **iff exactly one accepted offer is unaccounted while every other is
 * accounted**; any other shape (same-store tie ⇒ zero unaccounted, multiple unaccounted, or
 * unusable/missing evidence) is INCONCLUSIVE. **Fail-null beats fail-wrong** (the #745 doctrine): a
 * wrong auto-resolution silently mis-attributes money-adjacent counts, so every ambiguity falls to
 * Tier 2 rather than guessing.
 *
 * Pure and `Platform`-agnostic (P8): operates only on already-parsed store text; no `Platform` branch,
 * no wall clock. So the projector's fold reproduces the same resolution on a from-zero refold.
 */
object JobAcceptMismatchResolver {

    /** One accepted offer's store identity for the join — its `offer_record` PK + its parsed store. */
    data class AcceptedOffer(
        /** `offer_record.eventSequenceId` (the source `OFFER_ACCEPTED` sequenceId) — the resolve target. */
        val eventSequenceId: Long,
        /** The offer's parsed store (`offer_records.merchantName`); null/blank ⇒ unusable evidence. */
        val storeName: String?,
    )

    /**
     * Return the single orphan offer's `eventSequenceId`, or null when the mismatch is INCONCLUSIVE.
     *
     * @param acceptedOffers the closing job's accepted offers (PK + parsed store).
     * @param deliveredStoreForms every store-name string carried by the job's delivered drops (the
     *   `delivery_records.storeName` plus each `payoutStoreForms` entry) — the "surrounding screens"
     *   evidence, already committed to the read model at fold time.
     */
    fun resolveOrphan(
        acceptedOffers: List<AcceptedOffer>,
        deliveredStoreForms: List<String>,
    ): Long? {
        // A single-accept job has no orphan to disambiguate; need ≥2 accepts for a mismatch.
        if (acceptedOffers.size < 2) return null
        // Any accepted offer with no usable store text makes the join unreliable → fail-null (Tier 2).
        if (acceptedOffers.any { it.storeName.isNullOrBlank() }) return null

        val deliveredChains = deliveredStoreForms
            .filter { it.isNotBlank() }
            .map { StoreKeys.normalizedChain(it) }
            .toSet()
        // No delivered-store evidence to match against → nothing distinguishes the offers → Tier 2.
        if (deliveredChains.isEmpty()) return null

        val unaccounted = acceptedOffers.filter {
            StoreKeys.normalizedChain(it.storeName!!) !in deliveredChains
        }
        // Resolve iff EXACTLY one accepted offer is unaccounted while every other is store-accounted.
        return if (unaccounted.size == 1) unaccounted.single().eventSequenceId else null
    }
}
