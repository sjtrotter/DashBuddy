package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * Scans a [UiNode] tree for sensitive keywords (PII, financial data).
 *
 * Used by [InboxProcessorTest] to prevent accidental commits of raw
 * banking/identity data in snapshot files. Keywords are inlined from
 * the production sensitive screen rules in `rules.default.json`.
 */
object SnapshotSecurityScanner {

    /** Combined keywords from both sensitive screen rules. */
    private val SENSITIVE_KEYWORDS = listOf(
        // doordash.screen.sensitive.known
        "Bank Account",
        "Routing Number",
        "Social Security",
        "Direct Deposit",
        "Visa",
        "Mastercard",
        "ending in",
        "Linked accounts",
        "Statements & documents",
        "Emergency contact details",
        // doordash.screen.sensitive.catchall
        "Crimson",
        "Biometric",
        "Available Balance",
        "Tax Form",
        "Expiry",
        "Enter the code we sent",
        "t=completed_view",
    )

    data class ScanResult(
        val isToxic: Boolean,
        val triggers: List<Pair<String, String>> = emptyList()
    )

    fun scan(node: UiNode): ScanResult {
        val triggers = mutableListOf<Pair<String, String>>()
        walkAndFind(node, triggers)
        return ScanResult(isToxic = triggers.isNotEmpty(), triggers = triggers)
    }

    fun printReport(result: ScanResult) {
        if (!result.isToxic) return

        println("     STATUS: TOXIC (Sensitive Data Detected)")
        println("     EVIDENCE:")

        if (result.triggers.isEmpty()) {
            println("        (Matched by Screen Type, no specific keyword triggers found)")
        } else {
            result.triggers.forEach { (text, keyword) ->
                println("        Found \"$text\" (Matched: '$keyword')")
            }
        }
        println("     ACTION: Redact these values in the JSON file immediately.")
    }

    private fun walkAndFind(node: UiNode, results: MutableList<Pair<String, String>>) {
        val text = node.text ?: ""
        val keyword = SENSITIVE_KEYWORDS.firstOrNull { text.contains(it, ignoreCase = true) }

        if (keyword != null) {
            results.add(text to keyword)
        }
        node.children.forEach { walkAndFind(it, results) }
    }
}
