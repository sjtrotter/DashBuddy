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

    /**
     * Regex SHAPES that mark PII a bare keyword can't (the #803 blind class). Each
     * pairs a pattern with a synthetic "keyword" label for the trigger report.
     *
     * - `pin \d{3,}` — a residence-entry PIN embedded in a free-text delivery-
     *   instructions body. This is the exact fragment that reached a corpus
     *   candidate id-less (no viewId → no rule/test id-scrub) and survived every
     *   layer; making it a corpus-gate failure stops recurrence in ANY future
     *   fixture. Digit-adjacency (≥3 digits) keeps "PIN pad"/"pin it" clean.
     *
     * Deliberately NOT added: an embedded full-name bigram (`[A-Z][a-z]+ [A-Z][a-z]+`).
     * The scanner runs over the whole committed corpus, which legitimately carries
     * merchant/street/UI two-word capitalized phrases ("Maple Street", "Farmers
     * Market", "Complete Delivery"); a bigram scan would false-positive heavily on
     * driver-owned, non-PII text. The whole-value first-name+last-initial name shape
     * is already owned by [SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN] and pinned by
     * `CaptureRedactionCorpusTest` (FIX 4), so it needs no scanner duplicate.
     */
    private val SENSITIVE_SHAPES: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bpin\b[\s:#]*#?\s*\d{3,}""") to "pin-code-shape",
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
        // #803: keyword-invisible PII shapes (e.g. a "pin 4821" fragment).
        SENSITIVE_SHAPES.firstOrNull { (re, _) -> re.containsMatchIn(text) }?.let { (_, label) ->
            results.add(text to label)
        }
        node.children.forEach { walkAndFind(it, results) }
    }
}
