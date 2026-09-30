package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField

object SnapshotScreenDiagnostics {

    /**
     * #1147 review Z7 — the scrub-contract string fields the X-Ray deliberately does NOT show: the
     * ones no node predicate can anchor on (`state` is legacy, reachable only through
     * `hasStateDescription*`, and was never an X-Ray column; tooltip / error / uid have no predicate
     * at all). Every OTHER [UiNodeTextField] entry is a category automatically, so the next contract
     * field a predicate can read shows up here without an edit (the W2 class).
     */
    internal val EXCLUDED_FIELDS: Set<UiNodeTextField> = setOf(
        UiNodeTextField.STATE_DESCRIPTION,
        UiNodeTextField.TOOLTIP_TEXT,
        UiNodeTextField.ERROR_TEXT,
        UiNodeTextField.UNIQUE_ID,
    )

    /** The header for a field's category: its wire key (the JSON key an author greps the capture for). */
    internal fun headerFor(field: UiNodeTextField): String = "🔤 ${field.wire}"

    /** The id column (not a scrub-contract string field, so appended explicitly). */
    internal const val ID_HEADER = "🆔 id"

    /**
     * The X-Ray's candidate fields — one owner for both the candidate filter and the report
     * categories, DERIVED from the [UiNodeTextField] SSOT minus [EXCLUDED_FIELDS], plus the id column.
     */
    internal val CATEGORIES: List<Pair<String, (UiNode) -> String?>> =
        UiNodeTextField.entries.filter { it !in EXCLUDED_FIELDS }.map { field ->
            headerFor(field) to { node: UiNode -> node.scrubbableStrings().first { it.first == field }.second }
        } + (ID_HEADER to { node: UiNode -> node.viewIdResourceName })

    fun printXRay(node: UiNode, breadcrumbs: List<String> = emptyList()) {
        // 1. Breadcrumbs
        if (breadcrumbs.isNotEmpty()) {
            println("     🍞 BREADCRUMBS: " + breadcrumbs.joinToString(" -> "))
        }

        // 2. Node Analysis
        val report = categorize(node)

        if (report.isEmpty()) {
            println("     🔎 X-RAY: (Tree is empty or has no text/IDs)")
            return
        }

        println("     🔎 X-RAY (Top 5 items):")
        report.forEach { (header, items) -> printCategory(header, items) }
        println("        (Use 'UnknownScreenAnalysisTest' for deep inspection)")
    }

    /** Each category with its distinct non-blank values in DFS order; empty categories omitted. */
    internal fun categorize(root: UiNode): List<Pair<String, List<String>>> {
        val candidates = collectCandidates(root)
        return CATEGORIES.map { (header, read) ->
            header to candidates.mapNotNull(read).filter { it.isNotBlank() }.distinct()
        }.filter { it.second.isNotEmpty() }
    }

    private fun printCategory(header: String, distinct: List<String>) {
        println("        $header:")
        distinct.take(5).forEach { item ->
            // Clean vertical list, no truncation
            println("           • \"$item\"")
        }
        if (distinct.size > 5) {
            println("           ... (${distinct.size - 5} more)")
        }
    }

    private fun collectCandidates(root: UiNode): List<UiNode> {
        val results = mutableListOf<UiNode>()
        fun walk(node: UiNode) {
            if (CATEGORIES.any { (_, read) -> !read(node).isNullOrBlank() }) results.add(node)
            node.children.forEach { walk(it) }
        }
        walk(root)
        return results
    }
}
