package cloud.trotter.dashbuddy.data.event.status

/**
 * Represents the specific phase of a drop-off task.
 */
enum class DropoffStatus {
    /** Navigating to the customer location. */
    NAVIGATING,

    /** Arrived at the customer's location. */
    ARRIVED,

    /** Handing off or dropping at door. */
    COMPLETING,

    /** Delivery finished. */
    COMPLETED,

    /** Default fallback if parsing fails. */
    UNKNOWN
}