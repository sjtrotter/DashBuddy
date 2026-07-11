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
     * exclusive with [completedAt] (an abandoned task is NEVER completed). The marker is what the
     * PICKUP_CONFIRMED close-out sweep filters on (`unassignedAt == null`) — that filter is the
     * load-bearing defense against a fabricated confirm for an abandoned-but-arrived pickup. (An
     * abandoned drop is not "invisible" to the #596 physical-completion check per se: with
     * `completedAt` null it reads as un-accounted, which keeps the job OPEN — never a false complete;
     * the abandon removes the drop's own placeholder so the job can still close on the single-order
     * path.) Cleared to null by the resume self-heal when a genuine same-order frame reactivates the
     * task — but only while the job stayed OPEN; after a single-order abandon the job closes and a
     * later same-order frame mints a NEW jobId the resume lookup can't reach (documented residual,
     * #736 review; not widened). `@Serializable` default-null ⇒ old snapshots/journals decode
     * unchanged (no migration).
     */
    val unassignedAt: Long? = null,
    val recovered: Boolean = false,
)

/**
 * The **accountable-dropoff** predicate — a dropoff that may be attributed pay and may hold a job's
 * final shape open: it is a [TaskPhase.DROPOFF] the dasher did NOT unassign ([unassignedAt] null,
 * the #736 firewall) AND it bears a customer identity ([customerNameHash] or [customerAddressHash]
 * non-null, the #498 phantom firewall — a never-activated, customer-TBD placeholder has neither).
 *
 * SSOT (Principle 5): both the receipt-split denominator
 * (`DeliveryCompletionEffects.mintingDropoffTasks`) and the final-shape gate
 * ([OfferPayFallback.isFinalShape]) filter siblings through THIS one predicate, so the two chains
 * cannot drift (the #630 review wedge: `isFinalShape` used to include placeholders/unassigned drops
 * the mint set already excluded, so an un-mintable sibling could hold the gate shut forever — the
 * mid-stack under-attribution regression). Note the deliberate asymmetry the split still owns: the
 * #691 estimate's equal-split denominator (`OfferPayFallback.shareFor`) KEEPS placeholders (it is a
 * per-owed-order split), so it does NOT use this predicate.
 */
val Task.isAccountableDropoff: Boolean
    get() = phase == TaskPhase.DROPOFF &&
        unassignedAt == null &&
        (customerNameHash != null || customerAddressHash != null)
