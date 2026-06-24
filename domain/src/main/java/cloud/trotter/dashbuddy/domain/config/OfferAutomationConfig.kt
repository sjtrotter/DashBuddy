package cloud.trotter.dashbuddy.domain.config

/**
 * Rules for the "Offer Robot".
 * Read by the side-effect engine's evaluation loopback to decide instant actions.
 */
data class OfferAutomationConfig(
    val masterAutoPilotEnabled: Boolean = DEFAULT_MASTER,

    // Auto-Accept
    val autoAcceptEnabled: Boolean = DEFAULT_AUTO_ACCEPT,
    val autoAcceptMinPay: Double = DEFAULT_ACCEPT_MIN_PAY,
    val autoAcceptMinRatio: Double = DEFAULT_ACCEPT_MIN_RATIO, // $/mi

    // Auto-Decline
    val autoDeclineEnabled: Boolean = DEFAULT_AUTO_DECLINE,
    val autoDeclineMaxPay: Double = DEFAULT_DECLINE_MAX_PAY,
    val autoDeclineMinRatio: Double = DEFAULT_DECLINE_MIN_RATIO,

    /**
     * #577 "quick declines" / single-click declines: when ON, the second
     * ("are you sure?") decline button is auto-confirmed so a decline is one
     * tap/voice command. This is "finish the decline I started," NOT the
     * economics-driven auto-decline above ("decide for me") — independent of
     * [masterAutoPilotEnabled]; the setting itself is the dasher's consent.
     */
    val quickDeclinesEnabled: Boolean = DEFAULT_QUICK_DECLINES,
) {
    companion object {
        // ONE owner for every default (#401): the DataStore fallbacks
        // reference these instead of restating literals.
        const val DEFAULT_MASTER = false
        const val DEFAULT_AUTO_ACCEPT = false
        const val DEFAULT_ACCEPT_MIN_PAY = 10.0
        const val DEFAULT_ACCEPT_MIN_RATIO = 2.0
        const val DEFAULT_AUTO_DECLINE = false
        const val DEFAULT_DECLINE_MAX_PAY = 3.50
        const val DEFAULT_DECLINE_MIN_RATIO = 0.50
        const val DEFAULT_QUICK_DECLINES = false
    }
}