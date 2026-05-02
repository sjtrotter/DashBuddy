package cloud.trotter.dashbuddy.domain.state

/**
 * Whether the worker is navigating to or has arrived at the task location.
 */
enum class TaskSubFlow {
    NAVIGATION,
    ARRIVED,
}
