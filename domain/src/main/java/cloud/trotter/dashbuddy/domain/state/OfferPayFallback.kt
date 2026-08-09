package cloud.trotter.dashbuddy.domain.state

/**
 * The pure eligibility + share policy for the #691 offer-pay fallback estimate — the write-side
 * decision of whether (and how much of) a job's accepted-offer pay to stamp on a receipt-less
 * completion so it folds a real net row instead of a $0-unattributed one.
 *
 * Extracted out of `EffectMap` (which was already past the oversized bar) so the policy lives next
 * to its sibling [DropPayApportioner] in `:domain` and is unit-testable without the state machine.
 * `EffectMap` keeps a thin call: it computes the three region-derived inputs it alone can see — the
 * receipt-evidence verdict ([suppressedByReceipt], from the job-scoped pay-bearing `PostTaskFields`),
 * whether a final-shape gate applies at this mint site, and (#996) whether the job closed
 * proven-complete (`isJobPhysicallyComplete`) — and hands them here.
 *
 * The split itself has THREE arms, in order (see [shareFor]):
 * 1. the #996 **eligible-owed filter** — at a proven-complete close, a never-activated TBD
 *    placeholder leaves the denominator so its share lands on the drops that exist;
 * 2. the #997 **per-offer partition** — when every eligible drop's [Task.mintedByOfferHash] resolves,
 *    each accepted offer's own `payAmount` is split across its OWN drops (a job that absorbed N
 *    offers no longer pools them into one job-wide average);
 * 3. the **pooled degrade** — any unresolvable lineage falls back to today's single equal split of
 *    [Job.offerPayTotal], bit-exact. Degrading to `null` was rejected: `offerPayShare` is an
 *    explicitly-declared ESTIMATE basis, and "known Σ, unknown split" IS its stated semantics, so
 *    pooling is the honest degraded answer while un-attributing money we attribute today would be a
 *    regression and a guessed mapping would mis-price drops.
 *
 * Pure and side-effect-free; derives only from the passed records. No wall clock, no logging (the
 * caller owns the observability WARN, #691 FIX 6). Platform-agnostic: lineage is keyed by
 * `offerHash` and completeness by task structure — no `Platform` anywhere (P8).
 */
object OfferPayFallback {

    /**
     * The outcome of a fallback decision.
     *
     * @property share the drop's dollars, or null when no estimate is stamped.
     * @property eligibleButUnsplit true when every eligibility gate passed (a receipt-less,
     *   final-shape job) yet the split produced NO share for this drop — a pay-less offer
     *   ([Job.offerPayTotal] / the drop's own offer's `payAmount` null) or a minting task absent from
     *   the job's eligible owed dropoff set. The
     *   caller logs ONE PII-safe WARN on this (the silent-denominator-miss observability seam), so
     *   the quoted>delivered halving / pay-less-offer classes are visible instead of silent.
     * @property quotedOwed the size of [owedDropoffs] — every order the accepted quotes covered.
     * @property eligibleOwed the size of the denominator actually used, i.e. [quotedOwed] minus the
     *   #996 consolidation filter (equal to [quotedOwed] unless the job closed proven-complete with a
     *   never-activated placeholder outstanding). Counts only — the WARN's PII-safe context (P7).
     * @property perOffer true when the split ran PER ACCEPTED OFFER (#997 — every eligible drop's
     *   lineage resolved); false when it degraded to the pooled equal split.
     */
    data class Result(
        val share: Double?,
        val eligibleButUnsplit: Boolean,
        val quotedOwed: Int = 0,
        val eligibleOwed: Int = 0,
        val perOffer: Boolean = false,
    )

    private val NONE = Result(share = null, eligibleButUnsplit = false)

    /**
     * The fallback share for [mintingTaskId] of [job].
     *
     * @param job the closing/exiting job.
     * @param recentTasks the region's task lifecycle history — the denominator's second source
     *   ([owedDropoffs]): `job.tasks` no longer retains an UNASSIGNED drop (the #752 reconcile keeps
     *   the placeholder mirror to outstanding work), so the owed-order count must union it back in
     *   from the lifecycle record or a 2-order job with one unassigned drop would split the FULL
     *   quote over 1 (estimate over-count).
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
     * @param jobProvenComplete the #996 consolidation proof, computed at the mint site from
     *   `isJobPhysicallyComplete` (`:core:state`). True ⇒ this job is done and nothing more can
     *   deliver, so a never-activated TBD placeholder in the denominator is a *consolidation
     *   artifact* and is dropped (see [eligibleOwedDropoffs]). False (abandon, endSession bail,
     *   mid-flight) ⇒ today's conservative dilution stands and the remainder rides the already-
     *   surfaced `unattributedPay` bucket.
     */
    fun shareFor(
        job: Job,
        recentTasks: List<Task>,
        mintingTaskId: String,
        suppressedByReceipt: Boolean,
        requireFinalShape: Boolean,
        jobProvenComplete: Boolean,
    ): Result {
        if (suppressedByReceipt) return NONE

        if (requireFinalShape && !isFinalShape(job, mintingTaskId)) {
            return NONE // mid-stack exit — not the last open owed drop
        }

        val quoted = owedDropoffs(job, recentTasks)
        val eligible = eligibleOwedDropoffs(quoted, jobProvenComplete)
        // #997: per-offer only when EVERY eligible drop's lineage resolves to one of this job's
        // accepted offers. A single unresolvable drop makes the whole partition a guess, so the
        // split degrades WHOLESALE to the pooled arm (below) rather than mixing two bases.
        val perOffer = eligible.isNotEmpty() && eligible.all { acceptedOfferFor(job, it) != null }
        val shares = if (perOffer) {
            perOfferShares(job, eligible)
        } else {
            DropPayApportioner.equalSplit(job.offerPayTotal, eligible)
        }
        val share = shares[mintingTaskId]
        // Eligible (all gates passed) but the split yielded nothing: pay-less offer or a minting task
        // outside the eligible owed set. Surface it (FIX 6) rather than silently dropping the estimate.
        // A minting task can never be removed BY the #996 filter (every mint site requires an
        // identity-bearing drop, which the filter's all-hashes-null test excludes), so an unsplit miss
        // still means what it meant pre-#996.
        return Result(
            share = share,
            eligibleButUnsplit = share == null,
            quotedOwed = quoted.size,
            eligibleOwed = eligible.size,
            perOffer = perOffer,
        )
    }

    /**
     * The estimate denominator — every order the accepted-offer quote covered: a per-owed-order equal
     * split over the offer's order count, NOT the mint set, so it DELIBERATELY KEEPS placeholders and
     * unassigned drops (an unassigned order was still quoted; dropping it would over-attribute the
     * survivors). This is the intentional asymmetry with [isFinalShape] / `mintingDropoffTasks`,
     * which filter to accountable drops via [Task.isAccountableDropoff] — do NOT collapse the two
     * chains onto one filter.
     *
     * Sourced from the UNION of `job.tasks` dropoffs ∪ [recentTasks] dropoffs of this job (ANY
     * marker state — unassigned included), deduped by taskId: since the #752 reconcile, `job.tasks`
     * is the OUTSTANDING-placeholder mirror and structurally cannot retain an unassigned drop, so
     * the lifecycle record is the only place that order still exists. The union restores the stable
     * per-owed-order-at-accept count (total/N) regardless of where the reconcile keeps tasks, and is
     * robust to future task-set churn. Public so the caller's observability WARNs count the same set.
     *
     * The #996 filter ([eligibleOwedDropoffs]) is applied ON TOP of this set, never inside it — the
     * quoted-order count stays the honest denominator whenever completion is unproven.
     */
    fun owedDropoffs(job: Job, recentTasks: List<Task>): List<Task> =
        (job.tasks.filter { it.phase == TaskPhase.DROPOFF } +
            recentTasks.filter { it.jobId == job.jobId && it.phase == TaskPhase.DROPOFF })
            .distinctBy { it.taskId }

    /**
     * #996 — the *eligible* owed set: [owed] minus every **never-activated TBD placeholder**, but
     * ONLY when the job closed proven-complete ([shareFor]'s `jobProvenComplete`).
     *
     * A receipt-less same-customer multi-order job mints one dropoff placeholder per ORDER yet only
     * ever ACTIVATES one physical drop (every later same-hash frame resumes the one taskId, #498), so
     * the leftover placeholder permanently diluted the split — the 08-07 job stamped
     * `13.10 / 2 = 6.55` on its sole drop and the other \$6.55 had no drop to land on (the reported
     * total minus attributed reconciled to exactly that). At a proven-complete close such a
     * placeholder is a **consolidation artifact** that can never mint (#749's whole finding — the
     * per-customer coverage arm exists BECAUSE the placeholder count desyncs from the physical
     * drops), so its share must land on the drops that exist.
     *
     * Two deliberate non-filters:
     * - **Completion unproven → no filter at all.** An abandon, an `endSession` bail or a mid-flight
     *   exit may still owe a drop, so dilution stays and the remainder keeps riding the named
     *   `unattributedPay` bucket. Re-attributing there would over-count.
     * - **An UNASSIGNED drop is NEVER filtered**, whatever the proof says — the [owedDropoffs]
     *   doctrine holds: that order was quoted but may not be paid, so paying its share to a sibling
     *   would over-attribute (it is also structurally excluded here by `unassignedAt == null`).
     *
     * Lineage-independent by construction, so it heals a recovered / pre-#997 job too.
     */
    fun eligibleOwedDropoffs(owed: List<Task>, jobProvenComplete: Boolean): List<Task> =
        if (!jobProvenComplete) {
            owed
        } else {
            owed.filterNot { t ->
                t.customerNameHash == null && t.customerAddressHash == null &&
                    t.unassignedAt == null && t.completedAt == null
            }
        }

    /**
     * #997 — the per-offer split: partition [eligible] by each drop's [Task.mintedByOfferHash] and
     * run [DropPayApportioner.equalSplit] of that offer's OWN `payAmount` across its own partition.
     *
     * A job that absorbed N separately-accepted offers pooled their pay into one job-wide equal split
     * (08-06: accepts of \$10.45 / \$16.55 / \$20.20 each folded \$15.73 — a per-drop error up to
     * \$5.28 at 50 % relative, invisible at session level because Σ is unchanged). The mapping was
     * never missing, only unrecorded: each accept mints its own placeholder batch, so stamping the
     * minting offer's hash onto the slot recovers it exactly.
     *
     * Properties:
     * - **Cents-exact per partition** — [DropPayApportioner.equalSplit]'s remainder-to-last runs
     *   inside each offer's own partition, so Σ shares = Σ{`payAmount` of offers with ≥1 eligible
     *   drop} to the cent, and can never exceed the accepted quotes.
     * - **A pay-less offer's partition gets nothing and no longer dilutes its siblings** (strictly
     *   more honest than the pooled arm, which spread the remaining offers' pay across it).
     * - **An offer with an EMPTY partition contributes nothing** — the cross-offer same-customer
     *   consolidation residual (offer B's sole order collapsing onto offer A's drop). Its dollars stay
     *   visible in the unattributed bucket rather than being guessed onto a sibling (#745's
     *   fail-null-beats-fail-wrong applied to an identity claim).
     */
    private fun perOfferShares(job: Job, eligible: List<Task>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>(eligible.size)
        eligible.groupBy { it.mintedByOfferHash }.forEach { (_, partition) ->
            val pay = partition.firstNotNullOfOrNull { acceptedOfferFor(job, it) }?.payAmount
            out.putAll(DropPayApportioner.equalSplit(pay, partition))
        }
        return out
    }

    /** The accepted offer that minted [task], or null when the lineage is absent/unresolvable. */
    private fun acceptedOfferFor(job: Job, task: Task): AcceptedOfferEconomics? =
        task.mintedByOfferHash?.let { hash -> job.acceptedOffers.firstOrNull { it.offerHash == hash } }

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
