package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.SensitiveScreenMatcher

object SnapshotSecurityScanner {
    // Single source of truth for keywords
    private val SENSITIVE_KEYWORDS = SensitiveScreenMatcher.SENSITIVE_KEYWORDS

    data class ScanResult(
        val isToxic: Boolean,
        val triggers: List<Pair<String, String>> = emptyList()
    )

    fun scan(node: UiNode): ScanResult {
        // 1. Run the official matcher first
        val matcher = SensitiveScreenMatcher()
        val match = matcher.matches(node)

        // 2. Scan for individual triggers regardless of the matcher result
        // (We want to know WHAT matched, even if the matcher caught it)
        val triggers = mutableListOf<Pair<String, String>>()
        walkAndFind(node, triggers)

        // It is toxic if the Matcher says so OR if we found keywords manually
        val isToxic = (match is ScreenInfo.Sensitive) || triggers.isNotEmpty()

        return ScanResult(isToxic = isToxic, triggers = triggers)
    }

    /**
     * Standardized reporting to keep your logs clean and consistent.
     */
    fun printReport(result: ScanResult) {
        if (!result.isToxic) return

        println("     🚨 STATUS: TOXIC (Sensitive Data Detected)")
        println("     🔍 EVIDENCE:")

        if (result.triggers.isEmpty()) {
            println("        • (Matched by Screen Type, no specific keyword triggers found)")
        } else {
            result.triggers.forEach { (text, keyword) ->
                println("        • Found \"$text\" (Matched: '$keyword')")
            }
        }
        println("     📝 ACTION: Redact these values in the JSON file immediately.")
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