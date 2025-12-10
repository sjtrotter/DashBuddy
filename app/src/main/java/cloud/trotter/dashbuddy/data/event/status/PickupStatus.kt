package cloud.trotter.dashbuddy.data.event.status

/**
 * Represents the specific phase of a pickup task.
 */
enum class PickupStatus {
    /** Navigating to the store location. */
    NAVIGATING,

    /** Arrived at the store / Waiting for order. */
    ARRIVED,

    /** Actively shopping for items (Shop & Deliver). */
    SHOPPING,

    /** Order picked up and verified. */
    CONFIRMED,

    /** Default fallback if parsing fails. */
    UNKNOWN
}