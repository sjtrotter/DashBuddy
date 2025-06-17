package cloud.trotter.dashbuddy.data.order

/**
 * Status of the offer.
 */
enum class OrderStatus {
    /** Pending, not yet started. */
    PENDING,

    /** Navigating to pickup location. */
    PICKUP_NAVIGATING,

    /** Arrived at pickup location. */
    PICKUP_ARRIVED_AT_STORE,

    /** Confirmed pickup. */
    PICKUP_CONFIRMED,

    /** Navigating to dropoff location. */
    DROPOFF_NAVIGATING,

    /** Arrived at dropoff location. */
    DROPOFF_ARRIVED_AT_CUSTOMER,

    /** Confirmed dropoff. */
    DROPOFF_CONFIRMED,

    /** Completed. */
    COMPLETED,

    /** Cancelled. */
    CANCELLED
}