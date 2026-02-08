package cloud.trotter.dashbuddy.pipeline.features.click

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.click.ClickAction
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

        // --- IGNORE EVERYTHING ELSE ---
        return ClickAction.UNKNOWN
    }
}