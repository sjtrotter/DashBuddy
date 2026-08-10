package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

/**
 * Which arm of [OfferPayFallback]'s attribution ladder produced a drop's `offerPayShare` (#997
 * amendment C). Rides the `DELIVERY_COMPLETED` payload so a frozen estimate row is auditable — "how
 * confidently was this drop priced?" — and drives the PII-safe degrade DEBUG at the mint site.
 *
 * Ordered strongest → weakest.
 */
@Serializable
enum class OfferPayAttribution {
    /** The drop's store matched EXACTLY ONE accepted offer's stores — that offer's quote, split over its own drops. */
    PER_OFFER_STORE,

    /** The drop's store matched ≥2 accepted offers (two same-store quotes): their quotes SUB-POOLED over their drops. */
    SUB_POOLED_STORE,

    /**
     * An accepted offer whose own orders CONSOLIDATED onto this drop (same customer, no drop of its
     * own) was folded in, so this drop carries its quote too — #996's doctrine applied inside the
     * ladder: the physical drop that carried the order gets the money.
     */
    CONSOLIDATED_CUSTOMER,

    /** The drop carried no usable store, so it joined the component of the offer that MINTED its slot. */
    STAMP_FALLBACK,

    /** Neither store nor slot lineage resolved: the remaining quotes pooled over the remaining drops. */
    JOB_POOLED,

    /** The conservative inline (PostTask-exit) mint — always a job-wide pooled split over the quoted orders. */
    INLINE_POOLED,
}

/**
 * The pure eligibility + share policy for the #691 offer-pay fallback estimate — the write-side
 * decision of whether (and how much of) a job's accepted-offer pay to stamp on a receipt-less
 * completion so it folds a real net row instead of a $0-unattributed one.
 *
 * Extracted out of `EffectMap` (which was already past the oversized bar) so the policy lives next
 * to its sibling [DropPayApportioner] in `:domain` and is unit-testable without the state machine.
 * `EffectMap` keeps a thin call: it computes the region-derived inputs it alone can see — the
 * receipt-evidence verdict (`suppressedByReceipt`, from the job-scoped pay-bearing `PostTaskFields`),
 * the per-site final-shape gate, and (at the close only) whether the job is PROVEN complete — then
 * delegates the eligibility + split and owns the observability logging.
 *
 * ## Two mint sites, two policies (#997 amendment B)
 *
 * - **[shareFor] — the INLINE (PostTask-exit) mint.** The job may still be OPEN, so nothing about its
 *   final shape is known: it keeps the pre-#996/#997 **conservative pooled split over the QUOTED owed
 *   orders**, unchanged. Shrinking the denominator or attributing per-offer here would let a
 *   full-quote inline stamp be followed by a later activation of a drop that had been filtered out —
 *   Σ stamped > Σ quoted, the one thing this policy must never permit.
 * - **[closeAttribution] — the TERMINAL close.** The job is closed and its shape is final, so the
 *   whole attribution is computed ONCE here and indexed per drop.
 *
 * ## The close ladder (#997 amendment A) — stores are authoritative, the slot stamp is a HINT
 *
 * [Task.mintedByOfferHash] records which accept CREATED a placeholder, not which offer's order the
 * activated drop turned out to be: dropoff placeholders activate **blind first-open**, so when the
 * platform routes an add-on's drop first, a mint-time stamp confidently names the WRONG offer — and
 * folding the wrong offer's exact quote per row is strictly worse than the pooled average, because Σ
 * is unchanged and reconciliation can never see it. The drop's **store** (reconciled by close time)
 * is the correspondence that survives that reshuffle, so:
 *
 * 1. **Store correspondence.** Offers and drops are grouped into components by normalized store form
 *    ([StoreKeys.normalizedChain], the #159 SSOT — no second normalizer, no platform literals). A
 *    component holding exactly ONE offer splits that offer's quote over its drops
 *    ([OfferPayAttribution.PER_OFFER_STORE]); a component holding N>1 same-store offers **sub-pools**
 *    (Σ quotes ÷ its drops, [OfferPayAttribution.SUB_POOLED_STORE]) — two Target offers against two
 *    Target drops is genuinely unknowable platform-side, and sub-pooling is both honest and strictly
 *    tighter than job-wide pooling (the fielded 08-06 job: H-E-B takes its exact \$20.20 while the two
 *    Targets pool at \$13.50 each, instead of all three folding \$15.73).
 * 2. **Stamp fallback.** A drop with no usable store (the fielded D6 join-miss class) joins the
 *    component of the offer that minted its slot, when that offer exists
 *    ([OfferPayAttribution.STAMP_FALLBACK]).
 * 2b. **Consolidation redistribution** ([OfferPayAttribution.CONSOLIDATED_CUSTOMER], proven-complete
 *    closes only). An offer left with NO drop because its order collapsed onto a sibling's drop
 *    (#498's same-customer resume-collapse, which the #996 shrink then retires) hands its quote to the
 *    component whose drops carry that customer — evidenced from the PICKUP side exactly as #749's
 *    coverage arm does it. Without this the ladder would pay LESS on a same-customer stack than the
 *    pooled degrade pays on the identical shape, i.e. more money for less information.
 * 3. **Job-wide pooled** ([OfferPayAttribution.JOB_POOLED]) — bit-exactly the pre-#997 behaviour, and
 *    a **wholesale** degrade: a single drop the ladder can place on neither a store nor a stamp sends
 *    the ENTIRE job here, because such a drop might belong to any quote and, most importantly, an
 *    UNASSIGNED order must keep diluting ([owedDropoffs]'s doctrine) — partitioning around it would
 *    quietly drop it out of the denominator and inflate every resolved drop's share. Degrading to
 *    `null` was rejected: `offerPayShare` is an explicitly declared ESTIMATE basis and "known Σ,
 *    unknown split" IS its stated semantics, so pooling is the honest degraded answer, while
 *    un-attributing money we attribute today would be a regression.
 *
 * A cross-store swap therefore AUTO-CORRECTS (store beats stamp), and an unparsed-offer job with no
 * lineage at all still attributes correctly whenever its stores are unique.
 *
 * **Σ invariant (structural):** every accepted quote is assigned to at most one component, and each
 * component's quote is split only across its own drops, so Σ stamped ≤ Σ accepted quotes always. A
 * quote the ladder can place on NO drop — rung 2b found no matching customer, or an ambiguous spread —
 * contributes nothing rather than being guessed onto a sibling (#745, fail-null beats fail-wrong);
 * its dollars stay visible in the session's `unattributedPay` bucket and it is COUNTED
 * ([ClosePlan.unattributedOffers]) so the caller can WARN, because the per-drop
 * [Result.eligibleButUnsplit] signal structurally cannot see it (a dropless offer has no minting
 * task).
 *
 * Pure and side-effect-free; derives only from the passed records. No wall clock, no logging (the
 * caller owns the FIX-6 WARN and the degrade DEBUG). Platform-agnostic: lineage is keyed by
 * `offerHash`, correspondence by normalized store text, completeness by task structure — no
 * `Platform` anywhere (P8).
 */
object OfferPayFallback {

    /**
     * The split denominator as measured — `null` whenever no measurement happened (a
     * receipt-suppressed or non-final-shape mint), so the caller can never report a fabricated
     * `0 of 0`.
     *
     * @property quotedOwed every order the accepted quotes covered ([owedDropoffs]).
     * @property eligibleOwed the denominator actually used — [quotedOwed] minus the #996
     *   consolidation filter (equal to [quotedOwed] at the inline mint, and at any close that could
     *   not prove completion).
     */
    data class Denominator(val quotedOwed: Int, val eligibleOwed: Int)

    /**
     * The outcome of a fallback decision for ONE drop.
     *
     * @property share the drop's dollars, or null when no estimate is stamped.
     * @property eligibleButUnsplit true when every eligibility gate passed (a receipt-less,
     *   final-shape job) yet the split produced NO share for this drop — a pay-less offer/component
     *   or a minting task absent from the eligible owed set. The caller logs ONE PII-safe WARN on
     *   this (the silent-denominator-miss observability seam).
     * @property denominator the measured denominator, or null when nothing was measured.
     * @property attributedOfferHash the offer this drop was actually PAID FROM — non-null only when
     *   exactly one accepted offer backed its component. Null for every pooled/sub-pooled share.
     * @property arm which ladder arm resolved it; null when no share was stamped.
     * @property ownOfferPayPresent whether this drop's OWN backing quote(s) carried any pay — the
     *   FIX-6 WARN needs it to tell a pay-less component apart from a denominator miss. Null when
     *   nothing was measured.
     */
    data class Result(
        val share: Double?,
        val eligibleButUnsplit: Boolean,
        val denominator: Denominator? = null,
        val attributedOfferHash: String? = null,
        val arm: OfferPayAttribution? = null,
        val ownOfferPayPresent: Boolean? = null,
    )

    /** One PII-safe degrade record (counts only) for the caller's DEBUG line. */
    data class DegradeNote(val arm: OfferPayAttribution, val offers: Int, val drops: Int)

    /**
     * The whole terminal-close attribution, computed once and indexed per drop (also the efficiency
     * fix: the close-out loop must not re-run the ladder per completion).
     */
    data class ClosePlan(
        private val shares: Map<String, Result>,
        val denominator: Denominator?,
        val degrades: List<DegradeNote>,
        /**
         * How many accepted, PAY-BEARING offers the ladder could attribute to no drop at all — their
         * dollars stay in the session's `unattributedPay` bucket. The caller WARNs once per job on a
         * non-zero count: the per-drop `eligibleButUnsplit` signal structurally cannot cover this
         * case, because a collapsed offer has no minting task to report it.
         */
        val unattributedOffers: Int = 0,
        private val suppressed: Boolean,
    ) {
        /**
         * This drop's decision. A drop outside the eligible set is an eligible-but-unsplit MISS (the
         * FIX-6 signal); a suppressed plan yields the flat no-estimate answer for every drop.
         */
        fun resultFor(taskId: String): Result = when {
            suppressed -> NONE
            else -> shares[taskId] ?: Result(
                share = null, eligibleButUnsplit = true, denominator = denominator,
                ownOfferPayPresent = null,
            )
        }
    }

    private val NONE = Result(share = null, eligibleButUnsplit = false)

    /**
     * The INLINE (PostTask-exit) mint's share for [mintingTaskId] of [job] — the pre-#996/#997
     * conservative policy, deliberately unchanged (see the class KDoc, amendment B).
     *
     * @param job the exiting job (still possibly OPEN).
     * @param recentTasks the region's task lifecycle history — the denominator's second source
     *   ([owedDropoffs]): `job.tasks` no longer retains an UNASSIGNED drop (the #752 reconcile keeps
     *   the placeholder mirror to outstanding work), so the owed-order count must union it back in
     *   from the lifecycle record or a 2-order job with one unassigned drop would split the FULL
     *   quote over 1 (estimate over-count).
     * @param mintingTaskId the dropoff being completed at this mint.
     * @param suppressedByReceipt the receipt-evidence verdict: the job showed a PAY-BEARING receipt
     *   attributable to itself → NOT eligible (a real receipt is truth; an estimate would over-count
     *   under the read-side MAX-floored reconciliation). Computed at the region edge.
     * @param requireFinalShape when true, stamp ONLY if [mintingTaskId] is the LAST OPEN owed dropoff
     *   — i.e. every OTHER accountable dropoff of the job has already completed. This kills the
     *   estimate-then-late-receipt over-count, add-on drift, and cents-drift-across-mints: a mid-stack
     *   pay-less exit stays unstamped (→ `NONE` forever, its dollars ride the unattributed bucket —
     *   the class got nothing pre-#691 either, no regression).
     */
    fun shareFor(
        job: Job,
        recentTasks: List<Task>,
        mintingTaskId: String,
        suppressedByReceipt: Boolean,
        requireFinalShape: Boolean,
    ): Result {
        if (suppressedByReceipt) return NONE

        if (requireFinalShape && !isFinalShape(job, mintingTaskId)) {
            return NONE // mid-stack exit — not the last open owed drop
        }

        val owed = owedDropoffs(job, recentTasks)
        val share = DropPayApportioner.equalSplit(job.offerPayTotal, owed)[mintingTaskId]
        // Eligible (all gates passed) but the split yielded nothing: pay-less offer or a minting task
        // outside the owed set. Surface it (FIX 6) rather than silently dropping the estimate.
        return Result(
            share = share,
            eligibleButUnsplit = share == null,
            denominator = Denominator(quotedOwed = owed.size, eligibleOwed = owed.size),
            attributedOfferHash = null, // a pooled share is not attributable to one offer
            arm = OfferPayAttribution.INLINE_POOLED.takeIf { share != null },
            ownOfferPayPresent = job.offerPayTotal != null,
        )
    }

    /**
     * The TERMINAL close's whole attribution — the #996 denominator shrink then the #997 ladder (see
     * the class KDoc), computed once for every drop the close-out will mint.
     *
     * @param job the CLOSED job (its shape is final by construction — no final-shape gate applies).
     * @param recentTasks the region's lifecycle record, as for [shareFor]. It is also, together with
     *   `job.tasks` (the every-step-refreshed lineage mirror), the pool the ladder reads each drop's
     *   resolved store and identity from — a task's copies are consulted across BOTH, so a stale copy
     *   can never make a real drop look store-less or identity-less.
     * @param suppressedByReceipt as for [shareFor]; short-circuits before anything is measured.
     * @param jobProvenComplete the #996 consolidation proof from `isJobPhysicallyComplete`. True ⇒
     *   nothing more can deliver, so a placeholder that can NEVER mint is a consolidation artifact
     *   (#749's whole finding) and leaves the denominator. False (an `endSession`/abandon bail, an
     *   unproven close) ⇒ today's conservative dilution stands and the remainder keeps riding the
     *   already-surfaced `unattributedPay` bucket.
     */
    fun closeAttribution(
        job: Job,
        recentTasks: List<Task>,
        suppressedByReceipt: Boolean,
        jobProvenComplete: Boolean,
    ): ClosePlan {
        if (suppressedByReceipt) {
            return ClosePlan(emptyMap(), denominator = null, degrades = emptyList(), suppressed = true)
        }

        val copies = (job.tasks + recentTasks.filter { it.jobId == job.jobId }).groupBy { it.taskId }
        val quoted = owedDropoffs(job, recentTasks)
        val eligible = if (!jobProvenComplete) quoted else quoted.filterNot { canNeverMint(copies, it) }
        val denominator = Denominator(quotedOwed = quoted.size, eligibleOwed = eligible.size)

        val assignment = assignToComponents(job, eligible, copies, jobProvenComplete)
        val shares = LinkedHashMap<String, Result>(eligible.size)
        val degrades = mutableListOf<DegradeNote>()

        for (component in assignment.components) {
            val quote = component.offers.mapNotNull { it.payAmount }.takeIf { it.isNotEmpty() }?.sum()
            val split = DropPayApportioner.equalSplit(quote, component.drops.map { it.task })
            val soleOffer = component.offers.singleOrNull()
            for ((task, byStore) in component.drops) {
                val share = split[task.taskId]
                shares[task.taskId] = Result(
                    share = share,
                    eligibleButUnsplit = share == null,
                    denominator = denominator,
                    attributedOfferHash = soleOffer?.offerHash,
                    arm = when {
                        share == null -> null
                        component.consolidated -> OfferPayAttribution.CONSOLIDATED_CUSTOMER
                        !byStore -> OfferPayAttribution.STAMP_FALLBACK
                        component.offers.size == 1 -> OfferPayAttribution.PER_OFFER_STORE
                        else -> OfferPayAttribution.SUB_POOLED_STORE
                    },
                    ownOfferPayPresent = quote != null,
                )
            }
            if (component.consolidated) {
                degrades += DegradeNote(OfferPayAttribution.CONSOLIDATED_CUSTOMER, component.offers.size, component.drops.size)
            } else if (component.offers.size > 1) {
                degrades += DegradeNote(OfferPayAttribution.SUB_POOLED_STORE, component.offers.size, component.drops.size)
            }
            val stamped = component.drops.count { !it.byStore }
            if (stamped > 0) {
                degrades += DegradeNote(OfferPayAttribution.STAMP_FALLBACK, component.offers.size, stamped)
            }
        }

        if (assignment.pooledDrops.isNotEmpty()) {
            val quote = assignment.pooledOffers.mapNotNull { it.payAmount }.takeIf { it.isNotEmpty() }?.sum()
            val split = DropPayApportioner.equalSplit(quote, assignment.pooledDrops)
            for (task in assignment.pooledDrops) {
                val share = split[task.taskId]
                shares[task.taskId] = Result(
                    share = share,
                    eligibleButUnsplit = share == null,
                    denominator = denominator,
                    attributedOfferHash = null,
                    arm = OfferPayAttribution.JOB_POOLED.takeIf { share != null },
                    ownOfferPayPresent = quote != null,
                )
            }
            degrades += DegradeNote(
                OfferPayAttribution.JOB_POOLED, assignment.pooledOffers.size, assignment.pooledDrops.size,
            )
        }

        // Whatever the ladder could place on NO drop at all. Its dollars stay in the session's
        // `unattributedPay` bucket, and the per-drop unsplit signal cannot report it (there is no
        // minting task), so it is counted here for the caller's one-per-job WARN.
        val unattributed = if (assignment.pooledDrops.isEmpty()) {
            assignment.pooledOffers.count { it.payAmount != null }
        } else {
            0
        }

        return ClosePlan(shares, denominator, degrades, unattributed, suppressed = false)
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
     * The #996 shrink ([canNeverMint]) applies ON TOP of this set, at the terminal close only — the
     * quoted-order count stays the honest denominator everywhere else.
     */
    fun owedDropoffs(job: Job, recentTasks: List<Task>): List<Task> =
        (job.tasks.filter { it.phase == TaskPhase.DROPOFF } +
            recentTasks.filter { it.jobId == job.jobId && it.phase == TaskPhase.DROPOFF })
            .distinctBy { it.taskId }

    /**
     * #996 — the shrink criterion: can this owed dropoff **never mint a completion**, making it a
     * pure consolidation artifact at a proven-complete close?
     *
     * A receipt-less same-customer multi-order job mints one dropoff placeholder per ORDER yet only
     * ever ACTIVATES one physical drop (every later same-hash frame resumes the one taskId, #498), so
     * the leftover placeholder permanently diluted the split — the 08-07 job stamped `13.10 / 2` on
     * its sole drop and the dash's reconciliation read reported − attributed = exactly the missing
     * \$6.55. At a proven-complete close its share must land on the drops that exist.
     *
     * The criterion is the ONE named owner [Task.isNeverActivatedPlaceholder] — the complement of
     * [Task.isAccountableDropoff] (the SSOT every mint denominator already filters through,
     * Principle 5) — deliberately NOT a hand-written "hashes null AND completedAt null" triple, which
     * would let a **flicker-activated** identity-less placeholder (the fielded #498 class:
     * `completedAt` stamped by displacement, both hashes still null) stay in the denominator forever
     * even though it can never mint. "Can never mint" is the criterion; "was never touched" is not.
     * An **UNASSIGNED** drop is never shrunk out, whatever the proof says — the predicate's own
     * `unassignedAt` clause enforces the [owedDropoffs] doctrine.
     *
     * Evaluated across ALL copies of the task (mirror ∪ lifecycle record): a drop is shrunk out only
     * if EVERY copy reads un-activated, so a stale mirror copy can never shrink a real drop out.
     */
    private fun canNeverMint(copies: Map<String, List<Task>>, task: Task): Boolean {
        val all = (copies[task.taskId].orEmpty() + task)
        return all.all { it.isNeverActivatedPlaceholder }
    }

    /**
     * The **final-shape** predicate shared by #691 (the inline offer-pay estimate gate above) and #630
     * (the receipt-split gate at the PostTask-exit mint): is [mintingTaskId] the LAST OPEN
     * **accountable** dropoff of [job] — has every OTHER accountable dropoff already completed
     * (`completedAt != null`)?
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

    // ---------------------------------------------------------------------
    // The store-correspondence ladder (#997 amendment A)
    // ---------------------------------------------------------------------

    private data class AssignedDrop(val task: Task, val byStore: Boolean)

    private data class Component(
        val offers: List<AcceptedOfferEconomics>,
        val drops: List<AssignedDrop>,
        /** True when a CONSOLIDATED offer (no drop of its own) was folded in on customer evidence. */
        val consolidated: Boolean = false,
    )

    private data class Assignment(
        val components: List<Component>,
        val pooledOffers: List<AcceptedOfferEconomics>,
        val pooledDrops: List<Task>,
    )

    /**
     * Group [eligible] drops and [job]'s accepted offers into store-correspondence components, then
     * bucket everything the ladder could not place into the job-wide pool. ONE pass: offers are
     * indexed by hash once, each drop is resolved once.
     *
     * Components are the connected components of the bipartite offer↔normalized-store graph, so two
     * offers quoting the same store share a component (→ sub-pool) and a multi-store offer pulls all
     * of its stores into one component (→ its drops split its single quote, whichever of its stores
     * they landed on).
     */
    private fun assignToComponents(
        job: Job,
        eligible: List<Task>,
        copies: Map<String, List<Task>>,
        jobProvenComplete: Boolean,
    ): Assignment {
        val offers = job.acceptedOffers
        val union = DisjointSet()
        val storeNodes = HashSet<String>()
        offers.forEachIndexed { index, offer ->
            val offerNode = offerNode(index)
            offer.storeHints.mapNotNull(::chainOf).distinct().forEach { chain ->
                val node = storeNode(chain)
                storeNodes += node
                union.union(offerNode, node)
            }
        }
        // The stamp fallback needs an offer→component lookup; a hash claimed twice is impossible
        // (`appendAddOn` dedups by offerHash) but putIfAbsent keeps it total regardless.
        val offerNodeByHash = HashMap<String, String>()
        offers.forEachIndexed { index, offer ->
            offer.offerHash?.let { offerNodeByHash.putIfAbsent(it, offerNode(index)) }
        }

        val dropsByRoot = LinkedHashMap<String, MutableList<AssignedDrop>>()
        val pooledDrops = mutableListOf<Task>()
        for (task in eligible) {
            val chain = observedChain(copies, task)
            val storeRoot = chain?.let { storeNode(it) }?.takeIf { it in storeNodes }?.let(union::find)
            val stampRoot = task.mintedByOfferHash?.let { offerNodeByHash[it] }?.let(union::find)
            when {
                storeRoot != null ->
                    dropsByRoot.getOrPut(storeRoot) { mutableListOf() } += AssignedDrop(task, byStore = true)
                stampRoot != null ->
                    dropsByRoot.getOrPut(stampRoot) { mutableListOf() } += AssignedDrop(task, byStore = false)
                else -> pooledDrops += task
            }
        }

        // WHOLESALE degrade (the [owedDropoffs] doctrine's guard): a drop the ladder could place on
        // NEITHER a store NOR a mint stamp might belong to ANY of the quotes — most importantly an
        // UNASSIGNED order, which must keep diluting. Partitioning around it would quietly drop it
        // out of the denominator and inflate every resolved drop's share, so one unresolvable drop
        // sends the WHOLE job to the pre-#997 pooled split. (Rung 2 rescues the store-less-but-stamped
        // D6 class first, so reaching here means genuinely no evidence at all.)
        if (pooledDrops.isNotEmpty()) {
            return Assignment(components = emptyList(), pooledOffers = offers, pooledDrops = eligible)
        }

        val offersByRoot = offers.withIndex().groupBy({ union.find(offerNode(it.index)) }, { it.value })
        val dropless = offersByRoot.filterKeys { it !in dropsByRoot.keys }
        val consolidated = if (jobProvenComplete) {
            redistributeConsolidated(job, dropless, dropsByRoot, copies)
        } else {
            emptyMap()
        }

        val components = dropsByRoot.map { (root, drops) ->
            Component(
                offers = offersByRoot[root].orEmpty() + consolidated[root].orEmpty(),
                drops = drops,
                consolidated = consolidated.containsKey(root),
            )
        }
        val absorbed = consolidated.values.flatten().toSet()
        val pooledOffers = dropless.values.flatten().filterNot { it in absorbed }
        return Assignment(components, pooledOffers, pooledDrops)
    }

    /**
     * #996↔#997 reconciliation: fold a **consolidated** offer's quote into the component of the drop
     * that actually carried its order.
     *
     * When a same-customer add-on's order collapses onto an existing drop (#498's resume-collapse),
     * that offer's own placeholder never activates and the proven-complete shrink retires it — so the
     * offer ends up with an empty partition and its dollars would silently sit unattributed, while the
     * *pooled* degrade on the very same shape would have paid them out. Paying MORE on LESS
     * information is indefensible, and #996's doctrine already answers it: the physical drop that
     * carried the order gets the money.
     *
     * The evidence is the **pickup side**, exactly as #749's coverage arm uses it — a job's pickup
     * placeholders carry their order's store hint AND the customer hash resolved when the dasher
     * worked them, so a dropless offer's stores name the customer(s) its orders were for. The quote
     * moves only when those customers land on drops in **exactly one** component (fail-null beats
     * fail-wrong, #745): an ambiguous spread across components, or no matching customer at all, leaves
     * the offer unattributed and counted for the caller's WARN.
     *
     * Gated on [Job] completeness by the caller — mid-flight, an offer with no drop yet is simply
     * still being worked.
     */
    private fun redistributeConsolidated(
        job: Job,
        dropless: Map<String, List<AcceptedOfferEconomics>>,
        dropsByRoot: Map<String, List<AssignedDrop>>,
        copies: Map<String, List<Task>>,
    ): Map<String, List<AcceptedOfferEconomics>> {
        if (dropless.isEmpty() || dropsByRoot.isEmpty()) return emptyMap()
        // chain -> the customer hashes the job's pickups resolved at that store.
        val customersByChain = HashMap<String, MutableSet<String>>()
        copies.values.asSequence().flatten()
            .filter { it.phase == TaskPhase.PICKUP }
            .forEach { pickup ->
                val hash = pickup.customerNameHash ?: return@forEach
                listOfNotNull(pickup.storeName, pickup.expectedStoreHint)
                    .mapNotNull(::chainOf)
                    .forEach { customersByChain.getOrPut(it) { mutableSetOf() } += hash }
            }
        if (customersByChain.isEmpty()) return emptyMap()
        // root -> the customer hashes its drops carry.
        val customersByRoot = dropsByRoot.mapValues { (_, drops) ->
            drops.mapNotNullTo(HashSet()) { d ->
                copies[d.task.taskId].orEmpty().plus(d.task).firstNotNullOfOrNull { it.customerNameHash }
            }
        }

        val out = HashMap<String, MutableList<AcceptedOfferEconomics>>()
        for (offer in dropless.values.flatten()) {
            if (offer.payAmount == null) continue // nothing to move
            val customers = offer.storeHints.mapNotNull(::chainOf)
                .flatMapTo(HashSet()) { customersByChain[it].orEmpty() }
            if (customers.isEmpty()) continue
            val targets = customersByRoot.filterValues { it.any(customers::contains) }.keys
            val target = targets.singleOrNull() ?: continue // 0 or ambiguous → stay unattributed
            out.getOrPut(target) { mutableListOf() } += offer
        }
        return out
    }

    /** The drop's store, from the richest copy of it (the mirror may lag the lifecycle record). */
    private fun observedChain(copies: Map<String, List<Task>>, task: Task): String? =
        (copies[task.taskId].orEmpty() + task).firstNotNullOfOrNull { it.storeName?.let(::chainOf) }

    private fun offerNode(index: Int) = "o:$index"

    private fun storeNode(chain: String) = "s:$chain"

    /** The #159 SSOT normalization, applied to a hint/observed store name; null when unusable. */
    private fun chainOf(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() }
            ?.let { StoreKeys.normalizedChain(it) }
            ?.takeIf { it.isNotEmpty() }

    /** Minimal union-find over string node keys — pure, allocation-light, no external dependency. */
    private class DisjointSet {
        private val parent = HashMap<String, String>()

        fun find(node: String): String {
            var root = parent.getOrPut(node) { node }
            while (root != parent.getValue(root)) root = parent.getValue(root)
            var cursor = node
            while (cursor != root) {
                val next = parent.getValue(cursor)
                parent[cursor] = root
                cursor = next
            }
            return root
        }

        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }
    }
}
