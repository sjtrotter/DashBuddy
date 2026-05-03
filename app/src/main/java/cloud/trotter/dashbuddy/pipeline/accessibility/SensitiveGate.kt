package cloud.trotter.dashbuddy.pipeline.accessibility

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-classification filter that drops UI trees containing sensitive data
 * (banking, identity, payment). Runs before the classifier — sensitive
 * events never reach the state machine or capture system.
 *
 * The gate is the architectural enforcement point per ADR-0005.
 * The JSON ruleset also has sensitive rules (priority 0 and 999) for
 * classification, but this gate runs earlier to prevent capture.
 */
@Singleton
class SensitiveGate @Inject constructor() {

    companion object {
        val SENSITIVE_KEYWORDS = listOf(
            "Bank Account", "Routing Number", "Verify Identity", "Social Security",
            "Crimson", "Biometric", "Available Balance", "View card details",
            "Linked accounts", "Debit card", "Account number", "Statements & documents",
            "Card status", "Lock card", "Emergency contact details", "Withdraw",
            "Expiry", "Enter the code we sent", "t=completed_view"
        )
    }

    fun isSensitive(tree: UiNode): Boolean =
        tree.findNode { node ->
            SENSITIVE_KEYWORDS.any { keyword ->
                node.text?.contains(keyword, ignoreCase = true) == true
            }
        } != null
}
