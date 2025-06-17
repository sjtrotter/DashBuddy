package cloud.trotter.dashbuddy.data.offer

/**
 * Status of the offer.
 */
enum class OfferStatus {
    /** The initial state of an offer - seen by the user. */
    SEEN,

    /** The offer has been accepted by the user. */
    ACCEPTED,

    /** The offer has been declined by the user. */
    DECLINED_USER,

    /** The offer has been declined due to a timeout. */
    DECLINED_TIMEOUT,

    /** The offer has been missed by the user. */
    DECLINED_MISSED
}