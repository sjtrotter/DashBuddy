package cloud.trotter.dashbuddy.domain.state

/**
 * The pure eligibility + share policy for the #691 offer-pay fallback estimate — the write-side
 * decision of whether (and how much of) a job's accepted-offer pay to stamp on a receipt-less
 * completion so it folds a real net row instead of a $0-unattributed one.
 *
 * Extracted out of `EffectMap` (which was already past the oversized bar) so the policy lives next
 * to its sibling [DropPayApportioner] in `:domain` and is unit-testable without the state machine.
 * `EffectMap` keeps a thin call: it computes the two region-derived inputs it alone can see — the
 * receipt-evidence verdict ([suppressedByReceipt], from the job-scoped pay-bearing `PostTaskFields`)
 * and, per mint site, whether a final-shape gate applies — and hands them here.
 *
 * Pure and side-effect-free; derives only from the passed records. No wall clock, no logging (the
 * caller owns the observability WARN, #691 FIX 6).
 */
object OfferPayFallback {

    /**
     * The outcome of a fallback decision.
     *
     * @property share the drop's dollars, or null when no estimate is stamped.
     * @property eligibleButUnsplit true when every eligibility gate passed (a receipt-less,
     *   final-shape job) yet the equal split produced NO share for this drop — a pay-less offer
     *   ([Job.offerPayTotal] null) or a minting task absent from the job's owed dropoff set. The
     *   caller logs ONE PII-safe WARN on this (the silent-denominator-miss observability seam), so
     *   the quoted>delivered halving / pay-less-offer classes are visible instead of silent.
     */
    data class Result(val share: Double?, val eligibleButUnsplit: Boolean)

    private val NONE = Result(share = null, eligibleButUnsplit = false)

    /**
     * The fallback share for [mintingTaskId] of [job].
     *
     * @param job the closing/exiting job.
     * @param mintingTaskId the dropoff being completed at this mint.
     * @param suppressedByReceipt the receipt-evidence verdict: the job showed a PAY-BEARING receipt
     *   attributable to itself → NOT eligible (a real receipt is truth; an estimate would over-count
     *   under the read-side MAX-floored reconciliation). Computed at the region edge.
     * @param requireFinalShape when true (the PostTask-exit mint, whose job may still be open), stamp
     *   ONLY if [mintingTaskId] is the LAST OPEN owed dropoff — i.e. every OTHER dropoff of the job
     *   has already completed. This kills the estimate-then-late-receipt over-count, add-on drift,
     *   and cents-drift-across-mints: a mid-stack pay-less exit stays unstamped (→ `NONE` forever, its
     *   dollars ride the unattributed bucket — the class got nothing pre-#691 either, no regression).
     *   The #596 close-out mint passes false: the job is already closed, so its shape is final.
     */
    fun shareFor(
        job: Job,
        mintingTaskId: String,
        suppressedByReceipt: Boolean,
        requireFinalShape: Boolean,
    ): Result {
        if (suppressedByReceipt) return NONE

        if (requireFinalShape && !isFinalShape(job, mintingTaskId)) {
            return NONE // mid-stack exit — not the last open owed drop
        }

        // The denominator is the job's OWN owed dropoff set (deduped) — every owed order, so this
        // DELIBERATELY KEEPS placeholders and unassigned drops (it is a per-owed-order equal split
        // over the offer's order count, NOT the mint set). This is the intentional asymmetry with
        // [isFinalShape] / `mintingDropoffTasks`, which filter to accountable drops via
        // [Task.isAccountableDropoff] — do NOT collapse the two chains onto one filter.
        val ownDropoffs = job.tasks
            .filter { it.phase == TaskPhase.DROPOFF }
            .distinctBy { it.taskId }
        val share = DropPayApportioner.equalSplit(job.offerPayTotal, ownDropoffs)[mintingTaskId]
        // Eligible (all gates passed) but the split yielded nothing: pay-less offer or a minting task
        // outside the owed set. Surface it (FIX 6) rather than silently dropping the estimate.
        return Result(share = share, eligibleButUnsplit = share == null)
    }

    /**
     * The **final-shape** predicate shared by #691 (the offer-pay estimate gate above) and #630 (the
     * receipt-split gate at the PostTask-exit mint): is [mintingTaskId] the LAST OPEN **accountable**
     * dropoff of [job] — has every OTHER accountable dropoff already completed (`completedAt != null`)?
     *
     * The sibling scan is filtered through [Task.isAccountableDropoff] — the SAME identity+unassign
     * filter the mint denominator (`DeliveryCompletionEffects.mintingDropoffTasks`) uses (Principle 5,
     * SSOT), so the two chains cannot drift. Excluding [mintingTaskId] sidesteps the amdt#6 mirror
     * staleness of the just-finishing drop's own `completedAt`.
     *
     * The trade this filter restores (the #630 review fix — the prior "placeholders included" scan
     * wedged the gate shut forever):
     * - An identity-**RESOLVED** but still-undelivered sibling (a real, activated drop with a customer
     *   hash and null `completedAt`) STILL holds the shape NON-final and blocks a mid-stack split —
     *   that is the gate's whole purpose, unchanged.
     * - An **unresolved placeholder** sibling (customer-TBD, both hashes null — e.g. the #749
     *   same-customer double-order that never activates) or an **unassigned** sibling (#736) now reads
     *   the shape as final, because such a drop can never mint. This reverts that sub-shape to the
     *   pre-PR partial-split behaviour — the spec's accepted, not-DoorDash-fielded residual — rather
     *   than letting an un-mintable sibling under-attribute the receipt (mint `dropRealizedPay`/
     *   `postTaskFields` = null → the whole receipt folds `PayBasis.NONE`, a money-loss regression).
     *
     * Side effect on #691 (both reviewers agreed, correct direction): narrowing the sibling set lets
     * an OFFER_PAY estimate stamp in a dangling-placeholder shape that the old scan blocked.
     */
    fun isFinalShape(job: Job, mintingTaskId: String): Boolean =
        job.tasks
            .filter { it.isAccountableDropoff }
            .distinctBy { it.taskId }
            .filter { it.taskId != mintingTaskId }
            .all { it.completedAt != null }
}
