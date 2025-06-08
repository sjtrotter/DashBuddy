package cloud.trotter.dashbuddy.data.pay // Or a common 'enums' or 'model' package

/**
 * Represents the different ways a tip can be received for a delivery.
 */
enum class TipType {
    /** The tip amount shown on the initial delivery completion screen. */
    IN_APP_INITIAL,

    /** A tip added by the customer after the delivery has already been completed. */
    IN_APP_POST_DELIVERY,

    /** A cash tip received from the customer, to be entered manually by the dasher. */
    CASH
}
