package cloud.trotter.dashbuddy.state.model.config

/**
 * Rules for the "Offer Robot".
 * Used by DefaultEffectHandler/Reducer to decide instant actions.
 */
data class OfferAutomationConfig(
    val masterAutoPilotEnabled: Boolean = false,

    // Auto-Accept
    val autoAcceptEnabled: Boolean = false,
    val autoAcceptMinPay: Double = 10.0,
    val autoAcceptMinRatio: Double = 2.0, // $/mi

    // Auto-Decline
    val autoDeclineEnabled: Boolean = false,
    val autoDeclineMaxPay: Double = 3.50,
    val autoDeclineMinRatio: Double = 0.50
)