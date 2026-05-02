package cloud.trotter.dashbuddy.domain.state

/**
 * Accumulation of related [Task]s. A DoorDash batched delivery is one Job
 * (2–3 pickup Tasks + 2–3 dropoff Tasks). An Uber ride is one Job (1 pickup
 * + 1 dropoff).
 *
 * The offer's store names are **hints**, not sources of truth. The actual
 * authoritative store name comes from Task observations when the driver
 * enters `task:pickup:navigation`.
 */
data class Job(
    val jobId: String,
    val offerStoreHint: List<String>,
    val parentOfferHash: String?,
    val tasks: List<Task> = emptyList(),
    val startedAt: Long,
)
