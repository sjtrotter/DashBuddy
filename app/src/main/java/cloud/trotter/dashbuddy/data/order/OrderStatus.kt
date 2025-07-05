package cloud.trotter.dashbuddy.data.order

/**
 * Status of the offer.
 */
enum class OrderStatus(
    val isPickedUp: Boolean,
    val isDelivered: Boolean,
    val isCompleted: Boolean,
    val isCancelled: Boolean = false,
    val isUnassigned: Boolean = false,
) {
    /** Seen, not yet accepted. */
    SEEN(
        isPickedUp = false,
        isDelivered = false,
        isCompleted = false,
    ),

    /** Pending, not yet started. */
    PENDING(
        isPickedUp = false,
        isDelivered = false,
        isCompleted = false,
    ),

    /** Navigating to pickup location. */
    PICKUP_NAVIGATING(
        isPickedUp = false,
        isDelivered = false,
        isCompleted = false,
    ),

    /** Arrived at pickup location. */
    PICKUP_ARRIVED(
        isPickedUp = false,
        isDelivered = false,
        isCompleted = false,
    ),

    /** Confirmed pickup. */
    PICKUP_CONFIRMED(
        isPickedUp = true,
        isDelivered = false,
        isCompleted = false,
    ),

    /** Navigating to dropoff location. */
    DROPOFF_NAVIGATING(
        isPickedUp = true,
        isDelivered = false,
        isCompleted = false,
    ),

    /** Arrived at dropoff location. */
    DROPOFF_ARRIVED(
        isPickedUp = true,
        isDelivered = false,
        isCompleted = false,
    ),

    /** Confirmed dropoff. */
    DROPOFF_CONFIRMED(
        isPickedUp = true,
        isDelivered = true,
        isCompleted = false,
    ),

    /** Completed. */
    COMPLETED(
        isPickedUp = true,
        isDelivered = true,
        isCompleted = true,
    ),

    /** Cancelled. */
    CANCELLED(
        isPickedUp = false,
        isDelivered = false,
        isCompleted = false,
        isCancelled = true,
    ),

    /** Unassigned. */
    UNASSIGNED(
        isPickedUp = false,
        isDelivered = false,
        isCompleted = false,
        isUnassigned = true,
    ),
}