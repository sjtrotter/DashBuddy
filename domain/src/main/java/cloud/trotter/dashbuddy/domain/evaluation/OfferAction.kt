package cloud.trotter.dashbuddy.domain.evaluation

enum class OfferAction {
    ACCEPT,
    DECLINE,
    NOTHING,
    /** Score passed, but a merchant rule requires the dasher to manually review before accepting. */
    MANUAL_REVIEW,
}