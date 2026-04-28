package cloud.trotter.dashbuddy.domain.model.accessibility

/**
 * Typed result of click classification — analogous to [ScreenInfo] and [NotificationInfo].
 *
 * [ClickClassifier] maps a clicked [UiNode] to one of these subtypes. Consumers
 * (reducers, effect handlers) pattern-match instead of re-checking view IDs or text.
 *
 * Unknown clicks carry the raw node ID and text so new patterns can be identified
 * from field logs and promoted to first-class subtypes.
 */
sealed class ClickInfo {

    /** Dasher tapped the accept button on an offer popup. */
    data object AcceptOffer : ClickInfo()

    /** Dasher tapped the decline button on an offer popup. */
    data object DeclineOffer : ClickInfo()

    /** Dasher tapped "Arrived at store" on the pickup navigation screen. */
    data object ArrivedAtStore : ClickInfo()

    /**
     * Click could not be matched to a known action.
     * Raw node identifiers are preserved so new click patterns can be classified later.
     */
    data class Unknown(
        val nodeId: String?,
        val nodeText: String?,
    ) : ClickInfo()
}
