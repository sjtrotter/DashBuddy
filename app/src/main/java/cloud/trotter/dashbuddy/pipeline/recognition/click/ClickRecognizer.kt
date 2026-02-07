package cloud.trotter.dashbuddy.pipeline.recognition.click

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import javax.inject.Inject

class ClickRecognizer @Inject constructor() {

    fun recognize(node: UiNode): ClickAction {

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