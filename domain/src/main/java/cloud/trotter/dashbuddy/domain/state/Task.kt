package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

/**
 * A single segment: one location, either navigation or arrival activity.
 * A pickup is one Task; a dropoff is one Task.
 */
@Serializable
data class Task(
    val taskId: String,
    val jobId: String,
    val phase: TaskPhase,
    val subPhase: TaskSubFlow? = null,
    /**
     * The offer's store-name **hint** for this order's pickup, stamped when the task is
     * pre-created from an accepted offer (#526). The hint is *not* authoritative — store
     * names on the offer card and on the pickup screen routinely differ — so [storeName]
     * (parsed from the pickup screen) remains the source of truth. The hint exists only to
     * route a pickup screen to the right pre-created order slot and to detect/repair a
     * mis-bound slot (the swap guard). Null for non-placeholder tasks and dropoff placeholders.
     */
    val expectedStoreHint: String? = null,
    val storeName: String? = null,
    val storeAddress: String? = null,
    val customerNameHash: String? = null,
    val customerAddressHash: String? = null,
    val deadlineMillis: Long? = null,
    val activity: String? = null,
    val itemsRemaining: Int? = null,
    val itemsShopped: Int? = null,
    val redCardTotal: Double? = null,
    val arrivedAt: Long? = null,
    val odometerAtEntry: Double? = null,
    val odometerAtArrival: Double? = null,
    val startedAt: Long,
    val completedAt: Long? = null,
    /**
     * The moment the dasher **abandoned** this task by unassigning the order (#736). Set by
     * `PlatformRegionStepper.abandonActiveTask` when a `Flow.TaskUnassigned` frame lands; mutually
     * exclusive with [completedAt] on the INLINE abandon path (that task is never completed), but the
     * CROSS-FRAME retro-mark deliberately pairs them (see below) — [unassignedAt] is the firewall, not
     * a null `completedAt`. The marker is what the
     * PICKUP_CONFIRMED close-out sweep filters on (`unassignedAt == null`) — that filter is the
     * load-bearing defense against a fabricated confirm for an abandoned-but-arrived pickup. Mutual
     * exclusion with [completedAt] holds for the INLINE abandon; the CROSS-FRAME retro-mark (#752)
     * deliberately stamps `unassignedAt` on a grace-retired task that already carries a `completedAt`,
     * so `unassignedAt != null` — not `completedAt == null` — is the SSOT firewall every consumer
     * (the close-out sweep + belt, `isJobPhysicallyComplete`, `isAccountableDropoff`) keys on. Cleared
     * to null by the resume self-heal when a genuine same-order frame reactivates the
     * task — but only while the job stayed OPEN; after a single-order abandon the job closes and a
     * later same-order frame mints a NEW jobId the resume lookup can't reach (documented residual,
     * #736 review; not widened). `@Serializable` default-null ⇒ old snapshots/journals decode
     * unchanged (no migration).
     */
    val unassignedAt: Long? = null,
    val recovered: Boolean = false,
    /**
     * The `offerHash` of the accepted offer that **created this slot** (#997) — a weak provenance
     * HINT, deliberately NOT an identity claim. Stamped by `JobAcceptFlow.preCreatedDropoffs` when a
     * job's placeholders are minted (fresh mint AND add-on absorption).
     *
     * **Why it is only a hint.** Dropoff placeholders activate **blind first-open** (`TaskLifecycle`
     * resolves an incoming customer onto the first hash-less DROPOFF slot), so when the platform
     * routes an add-on's drop first, the drop that lands in slot 1 is NOT slot 1's offer's order.
     * Treating the stamp as identity would fold the wrong offer's *exact* quote per row — Σ unchanged,
     * so reconciliation could never see it, which is strictly worse than a pooled average. The
     * authoritative correspondence is therefore the drop's **store** ([storeName], reconciled by close
     * time) against each accept's own [AcceptedOfferEconomics.storeHints]; this stamp is consulted
     * only as [OfferPayFallback]'s ladder rung 2 — the fallback for a drop that resolved NO store
     * (the D6 join-miss class) — where no better evidence exists.
     *
     * **Slot identity, not accumulation.** Like `taskId`/`jobId`/`phase`/[expectedStoreHint], it
     * describes the SLOT, so [swapTaskAccumulation] deliberately does NOT move it: the #526 swap
     * re-attributes screen observations between two order slots; each slot keeps the offer that
     * created it. (The #526 swap is a PICKUP-only mechanism, so it never touches a stamped dropoff
     * today — the exclusion is the durable statement of intent.)
     *
     * Dropoffs only (the split's denominator is dropoffs; pickup lineage has no consumer — YAGNI).
     * Null on every non-placeholder task, on all pre-#997 snapshots/journals, and whenever the
     * accepted offer carried no hash — each of which simply leaves the ladder to its store and pooled
     * arms, never to a guess. `@Serializable` default-null ⇒ old snapshots/journals decode unchanged
     * (no migration).
     */
    val mintedByOfferHash: String? = null,
)

/**
 * **The** customer-identity predicate — has this task ever ACTIVATED onto a real customer? True as
 * soon as either hash resolves ([customerNameHash] from the name surfaces, [customerAddressHash]
 * from the address ones); a never-activated, customer-TBD placeholder has neither.
 *
 * The single named owner (Principle 5) every identity test derives from: [isAccountableDropoff],
 * [isNeverActivatedPlaceholder], and `JobAcceptReconciliation`'s leftover-placeholder counter all
 * compose it rather than re-spelling it. They previously disagreed — the reconciliation counter
 * omitted [customerAddressHash], so a drop that resolved ONLY an address (a real, mintable physical
 * drop under [isAccountableDropoff]) was counted as an un-activated corpse on the
 * `JOB_ACCEPT_MISMATCH` tripwire. **Either hash is activation** is the deliberate semantics: the
 * mint firewall, the receipt-split denominator, and the final-shape gate have always treated an
 * address-only drop as real, so the diagnostic counter is the site that was wrong.
 */
val Task.hasCustomerIdentity: Boolean
    get() = customerNameHash != null || customerAddressHash != null

/**
 * The **accountable-dropoff** predicate — a dropoff that may be attributed pay and may hold a job's
 * final shape open: it is a [TaskPhase.DROPOFF] the dasher did NOT unassign ([unassignedAt] null,
 * the #736 firewall) AND it bears a customer identity ([hasCustomerIdentity], the #498 phantom
 * firewall).
 *
 * SSOT (Principle 5): the receipt-split denominator
 * (`DeliveryCompletionEffects.mintingDropoffTasks`), the final-shape gate
 * ([OfferPayFallback.isFinalShape]) and — via its complement [isNeverActivatedPlaceholder] — the
 * #996 estimate-denominator shrink all filter through THIS one predicate, so the chains cannot drift
 * (the #630 review wedge: `isFinalShape` used to include placeholders/unassigned drops the mint set
 * already excluded, so an un-mintable sibling could hold the gate shut forever — the mid-stack
 * under-attribution regression). Note the deliberate asymmetry the split still owns: the #691
 * estimate's owed-order denominator (`OfferPayFallback.owedDropoffs`) KEEPS placeholders and
 * unassigned drops (it is a per-QUOTED-order count), and only the proven-complete close shrinks it.
 */
val Task.isAccountableDropoff: Boolean
    get() = phase == TaskPhase.DROPOFF &&
        unassignedAt == null &&
        hasCustomerIdentity

/**
 * The **can-never-mint** predicate (#996) — the exact complement of [isAccountableDropoff] within
 * the non-unassigned dropoffs: a DROPOFF the dasher did not unassign that never activated onto a
 * customer, so no mint site will ever emit a `DELIVERY_COMPLETED` for it.
 *
 * Deliberately says nothing about [completedAt]: a **flicker-activated** placeholder (the fielded
 * #498 class — a displacement stamps `completedAt` while both hashes stay null) is still un-mintable,
 * and keying on "was never touched" instead of "can never mint" would leave it diluting the estimate
 * denominator forever. The [unassignedAt] clause is what keeps an ABANDONED order out of this set:
 * it was quoted and may still be paid, so its share must never be re-attributed to a sibling
 * ([OfferPayFallback.owedDropoffs]'s doctrine).
 */
val Task.isNeverActivatedPlaceholder: Boolean
    get() = phase == TaskPhase.DROPOFF &&
        unassignedAt == null &&
        !hasCustomerIdentity
