package cloud.trotter.dashbuddy.domain.state

/**
 * Which phase of a task the worker is in.
 * A Task is a single segment: one location, one purpose.
 */
enum class TaskPhase {
    PICKUP,
    DROPOFF,
}
