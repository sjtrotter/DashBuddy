package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class OfferMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.OFFER_POPUP
    override val priority = 20

    override fun matches(node: UiNode): Screen? {
        // Guard: loading state — no offer popup yet.
        if (node.findNode { it.viewIdResourceName?.endsWith("progress_bar") == true } != null) return null

        // Early return: "Are you sure you want to decline?" confirmation dialog.
        if (node.findNode { it.text?.contains("sure you want to decline", true) == true } != null) {
            return Screen.OFFER_POPUP_CONFIRM_DECLINE
        }

        // Must have Decline AND (Accept OR Add to route).
        val hasDecline = node.findNode { it.text.equals("Decline", ignoreCase = true) } != null
        val hasAccept = node.findNode {
            val txt = it.text
            txt.equals("Accept", true) || txt.equals("Add to route", true)
        } != null

        return if (hasDecline && hasAccept) targetScreen else null
    }
}