package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickAction
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
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