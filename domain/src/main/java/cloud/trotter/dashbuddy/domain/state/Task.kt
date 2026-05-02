package cloud.trotter.dashbuddy.domain.state

/**
 * A single segment: one location, either navigation or arrival activity.
 * A pickup is one Task; a dropoff is one Task.
 */
data class Task(
    val taskId: String,
    val jobId: String,
    val phase: TaskPhase,
    val storeName: String? = null,
    val customerNameHash: String? = null,
    val customerAddressHash: String? = null,
    val deadlineMillis: Long? = null,
    val activity: String? = null,
    val itemCount: Int? = null,
    val redCardTotal: Double? = null,
    val arrivedAt: Long? = null,
    val odometerAtEntry: Double? = null,
    val odometerAtArrival: Double? = null,
    val startedAt: Long,
    val completedAt: Long? = null,
    val recovered: Boolean = false,
)
