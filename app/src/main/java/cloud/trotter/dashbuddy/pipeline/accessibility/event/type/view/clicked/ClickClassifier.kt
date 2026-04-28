package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import timber.log.Timber
import javax.inject.Inject

/**
 * Classifies a clicked [UiNode] into a typed [ClickInfo] subtype.
 *
 * Analogous to [ScreenParser] and [NotificationClassifier] — first match wins.
 * [ClickInfo.Unknown] captures the raw node ID and text so unrecognized clicks
 * can be identified from field logs and promoted to first-class subtypes.
 */
class ClickClassifier @Inject constructor() {

    fun classify(node: UiNode): ClickInfo {

        // Accepting an offer
        if (node.hasId("accept_button")) {
            return ClickInfo.AcceptOffer
        }

        // Declining an offer
        if (node.hasText("Decline offer")) {
            return ClickInfo.DeclineOffer
        }

        // Arrived at store (pickup navigation)
        if (node.hasId("primary_action_button") &&
            (node.hasText("Arrived at store") || node.hasText("Arrived"))
        ) {
            return ClickInfo.ArrivedAtStore
        }

        // Unknown — log node identity for future classification
        val nodeId = node.viewIdResourceName?.takeIf { it.isNotBlank() && it != "no_id" }
        val nodeText = node.text?.takeIf { it.isNotBlank() }
        Timber.d("ClickClassifier: UNKNOWN — id=$nodeId text=$nodeText")
        return ClickInfo.Unknown(nodeId = nodeId, nodeText = nodeText)
    }
}
