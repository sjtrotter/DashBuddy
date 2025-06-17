package cloud.trotter.dashbuddy.state

/**
 * A sealed class to represent a parsed click event from the UI.
 * This provides a type-safe way to understand user actions.
 */
sealed class ClickInfo {
    /**
     * Represents an event that was not a click or was not relevant to our logic.
     */
    data object NoClick : ClickInfo()

    /**
     * An enumeration of the specific, actionable buttons we care about.
     */
    enum class ClickType {
        ACCEPT_OFFER,
        DECLINE_OFFER,
        VIEW_PAY_DETAILS,
        // You can add more types here as you identify them, e.g.:
        // END_DASH, PAUSE_DASH, RESUME_DASH
    }

    /**
     * Represents a click on a known, actionable button.
     * @property type The specific button that was clicked.
     */
    data class ButtonClick(val type: ClickType) : ClickInfo()

    /**
     * Represents a click event that occurred, but on a UI element
     * that we don't have specific logic for.
     */
    data object UnhandledClick : ClickInfo()
}