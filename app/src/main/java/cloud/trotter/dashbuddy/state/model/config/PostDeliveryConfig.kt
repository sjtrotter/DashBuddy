package cloud.trotter.dashbuddy.state.model.config

/**
 * Rules for the "Post-Dash Robot". -- DELETE after transition to Compose
 */
data class PostDeliveryConfig(
    val masterAutoPilotEnabled: Boolean = false,
    val autoExpandDetails: Boolean = true
    // val autoDismissPopup: Boolean = false, // future feature?
)