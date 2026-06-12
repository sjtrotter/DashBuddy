package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * Scans a [UiNode] tree for sensitive keywords (PII, financial data).
 *
 * Used by [InboxProcessorTest] to prevent accidental commits of raw
 * banking/identity data in snapshot files. The keyword list is the
 * production [SensitiveTextMarkers] SSOT (#432) — test toxicity and the
 * runtime UNKNOWN-capture scrub agree by construction.
 */
object SnapshotSecurityScanner {

    private val SENSITIVE_KEYWORDS = SensitiveTextMarkers.KEYWORDS

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
