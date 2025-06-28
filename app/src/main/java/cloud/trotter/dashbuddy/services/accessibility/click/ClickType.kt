package cloud.trotter.dashbuddy.services.accessibility.click

/**
 * An enumeration of the specific, actionable buttons we care about.
 */
enum class ClickType {
    ACCEPT_OFFER,
    DECLINE_OFFER,
    VIEW_PAY_DETAILS,
    DECLINE_ORDER,

    // You can add more types here as you identify them, e.g.:
    // END_DASH, PAUSE_DASH, RESUME_DASH
}