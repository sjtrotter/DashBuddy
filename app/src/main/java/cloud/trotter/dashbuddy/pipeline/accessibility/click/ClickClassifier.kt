package cloud.trotter.dashbuddy.pipeline.accessibility.click

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import javax.inject.Inject

class ClickClassifier @Inject constructor() {

    fun classify(node: UiNode): ClickAction {

        // --- Accepting an offer
        if (node.hasId("accept_button")) {
            return ClickAction.ACCEPT_OFFER
        }

        // --- Declining an offer
        if (node.hasText("Decline offer")) {
            return ClickAction.DECLINE_OFFER
        }

        if (node.hasId("primary_action_button") && (
                    node.hasText("Arrived at store") ||
                            node.hasText("Arrived")
                    )
        ) {
            return ClickAction.ARRIVED_AT_STORE
        }

        // --- IGNORE EVERYTHING ELSE ---
        return ClickAction.UNKNOWN
    }
}