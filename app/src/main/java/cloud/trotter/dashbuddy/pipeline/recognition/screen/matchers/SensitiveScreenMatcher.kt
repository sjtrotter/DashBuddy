package cloud.trotter.dashbuddy.pipeline.recognition.screen.matchers

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.screen.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenMatcher
import javax.inject.Inject

class SensitiveScreenMatcher @Inject constructor() : ScreenMatcher {
    override val targetScreen: Screen = Screen.SENSITIVE
    override val priority = 0

    companion object {
        // Public so tests can access it directly
        val SENSITIVE_KEYWORDS = listOf(
            "Bank Account", "Routing Number", "Verify Identity", "Social Security",
            "Crimson", "Biometric", "Available Balance", "View card details",
            "Linked accounts", "Debit card", "Account number", "Statements & documents",
            "Card status", "Lock card", "Emergency contact details", "Withdraw",
            "Expiry", "Enter the code we sent", "t=completed_view"
        )
    }

    override fun matches(node: UiNode): ScreenInfo? {
        val hasSensitiveText = node.findNode {
            SENSITIVE_KEYWORDS.any { keyword -> it.text?.contains(keyword, true) == true }
        } != null

        return if (hasSensitiveText) ScreenInfo.Sensitive(targetScreen) else null
    }
}