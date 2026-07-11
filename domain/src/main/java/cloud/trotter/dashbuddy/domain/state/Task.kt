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
     * exclusive with [completedAt] (an abandoned task is NEVER completed). Its presence makes the
     * task structurally invisible to the #596 physical-completion check and the PICKUP_CONFIRMED
     * close-out sweep, so an abandoned-but-arrived pickup can never fabricate a confirm. Cleared to
     * null by the resume self-heal when a genuine same-order frame reactivates the task.
     * `@Serializable` default-null ⇒ old snapshots/journals decode unchanged (no migration).
     */
    val unassignedAt: Long? = null,
    val recovered: Boolean = false,
)
