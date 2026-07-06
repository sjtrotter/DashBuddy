package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

/**
 * Economics captured from one accepted offer, at accept time. A [Job] accumulates one of
 * these per accepted offer, so an **add-on** accepted mid-delivery adds to (not replaces) the
 * job's pay/time/distance.
 *
 * Fields are nullable because the offer's [cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation]
 * may not have completed by the moment of accept; we capture the richest available source
 * (evaluation first, then the parsed offer).
 */
@Serializable
data class AcceptedOfferEconomics(
    val offerHash: String?,
    /** Gross pay shown on the offer screen. */
    val payAmount: Double? = null,
    /** Net pay after operating costs (from the offer evaluation), when available. */
    val netPay: Double? = null,
    /** Estimated minutes for this offer's route. */
    val estMinutes: Double? = null,
    /** Quoted distance in miles. */
    val distanceMiles: Double? = null,
    val acceptedAt: Long,
)

/**
 * Accumulation of related [Task]s. A DoorDash batched delivery is one Job
 * (2–3 pickup Tasks + 2–3 dropoff Tasks). An Uber ride is one Job (1 pickup
 * + 1 dropoff). An **add-on** offer accepted mid-delivery appends to the active
 * Job (same [jobId]) rather than starting a new one — see [acceptedOffers].
 *
 * The offer's store names are **hints**, not sources of truth. The actual
 * authoritative store name comes from Task observations when the driver
 * enters `task:pickup:navigation`.
 */
@Serializable
data class Job(
    val jobId: String,
    val offerStoreHint: List<String>,
    val parentOfferHash: String?,
    /** Economics of every offer accepted into this job (≥1 once started; >1 for stacked/add-ons). */
    val acceptedOffers: List<AcceptedOfferEconomics> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val startedAt: Long,
) {
    /** Total accepted gross pay across all offers in this job. */
    val totalPayAmount: Double get() = acceptedOffers.sumOf { it.payAmount ?: 0.0 }

    /**
     * Nullable total accepted gross pay — the numerator for the #691 offer-pay fallback estimate
     * (an equal split across the job's receipt-less drops). Unlike [totalPayAmount], which floors a
     * missing per-offer pay at 0.0, this returns null when NO offer carried a pay amount (a pay-less
     * offer must not stamp $0 estimate rows) and honestly UNDER-counts a partial-null stack (an
     * add-on with no captured pay contributes nothing). Follows the [blendedNetPay] nullability
     * precedent. Do not conflate with [totalPayAmount]; the live task-card economics still use that.
     */
    val offerPayTotal: Double?
        get() = acceptedOffers.mapNotNull { it.payAmount }.takeIf { it.isNotEmpty() }?.sum()

    /** Total accepted net pay (after operating costs) across all offers. */
    val totalNetPay: Double get() = acceptedOffers.sumOf { it.netPay ?: 0.0 }

    /** Total estimated minutes across all offers — the denominator for the job's blended $/hr. */
    val totalEstMinutes: Double get() = acceptedOffers.sumOf { it.estMinutes ?: 0.0 }

    /**
     * Net pay for the live task-card "Running at $/hr" co-hero (#460) — null when
     * no accepted offer has carried economics yet, so the card shows "—" rather
     * than a misleading $0.
     */
    val blendedNetPay: Double?
        get() = acceptedOffers.mapNotNull { it.netPay }.takeIf { it.isNotEmpty() }?.sum()

    /** Estimated minutes denominator for the blended $/hr — null until known, must be > 0. */
    val blendedEstMinutes: Double?
        get() = acceptedOffers.mapNotNull { it.estMinutes }.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it > 0.0 }

    /** Total quoted distance in miles across all offers. */
    val totalDistanceMiles: Double get() = acceptedOffers.sumOf { it.distanceMiles ?: 0.0 }

    /**
     * Distance denominator for the live "$/mi" task-card metric — null when no accepted offer has
     * carried a distance yet (or it sums to 0), so the card shows "—" rather than a misleading
     * $X/0mi. Mirrors [blendedEstMinutes] (#460 nullability discipline).
     */
    val blendedDistanceMiles: Double?
        get() = acceptedOffers.mapNotNull { it.distanceMiles }.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it > 0.0 }

    /** Every offer hash that contributed to this job (the add-on chain). */
    val parentOfferHashes: List<String> get() = acceptedOffers.mapNotNull { it.offerHash }

    /**
     * Swap the **accumulated screen state** between two of this job's tasks, identified by id,
     * while preserving each task's **slot identity** (`taskId`, `jobId`, `phase`,
     * [Task.expectedStoreHint], `startedAt`). The order-slot stays put; the observations that
     * were attributed to it move.
     *
     * This is the #526 swap guard: a pickup screen can be bound to the wrong pre-created order
     * because the offer's store hints don't reliably match the parsed pickup store, so the data
     * accumulated for one order may actually belong to its sibling. Swapping re-attributes it
     * without re-minting (ids stay stable for effects/db). No-op if either id is absent or they
     * are the same task. See [swapTaskAccumulation].
     *
     * CONTRACT: the swap moves lifecycle timestamps (`arrivedAt`, `completedAt`) too, so callers
     * must only swap two tasks in the **same** lifecycle state — the #526 trigger fires between two
     * OPEN (`completedAt == null`) pickups only. Swapping an active task with a completed one would
     * corrupt both; the call-site guard in `PlatformRegionStepper` enforces this.
     */
    fun withSwappedAccumulation(taskIdA: String, taskIdB: String): Job {
        if (taskIdA == taskIdB) return this
        val a = tasks.firstOrNull { it.taskId == taskIdA } ?: return this
        val b = tasks.firstOrNull { it.taskId == taskIdB } ?: return this
        val (newA, newB) = swapTaskAccumulation(a, b)
        return copy(tasks = tasks.map {
            when (it.taskId) {
                taskIdA -> newA
                taskIdB -> newB
                else -> it
            }
        })
    }
}

/**
 * Exchange the accumulated, screen-derived observations of two tasks while keeping each task's
 * durable slot identity (`taskId`, `jobId`, `phase`, [Task.expectedStoreHint], `startedAt`).
 * Pure; the basis of the #526 swap guard (see [Job.withSwappedAccumulation]). Returns the pair
 * in the same order it was given: `first` keeps a's identity with b's data, `second` keeps b's
 * identity with a's data.
 */
fun swapTaskAccumulation(a: Task, b: Task): Pair<Task, Task> {
    fun Task.withAccumulationOf(other: Task) = copy(
        subPhase = other.subPhase,
        storeName = other.storeName,
        storeAddress = other.storeAddress,
        customerNameHash = other.customerNameHash,
        customerAddressHash = other.customerAddressHash,
        deadlineMillis = other.deadlineMillis,
        activity = other.activity,
        itemsRemaining = other.itemsRemaining,
        itemsShopped = other.itemsShopped,
        redCardTotal = other.redCardTotal,
        arrivedAt = other.arrivedAt,
        odometerAtEntry = other.odometerAtEntry,
        odometerAtArrival = other.odometerAtArrival,
        completedAt = other.completedAt,
        recovered = other.recovered,
    )
    return a.withAccumulationOf(b) to b.withAccumulationOf(a)
}
