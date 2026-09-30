package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

object SnapshotScreenDiagnostics {

    /**
     * The X-Ray's candidate fields — one owner for both the candidate filter and the report
     * categories. #1147 review W2: an id-less Compose sheet whose ONLY handle is a pane title,
     * a click-action label, a role or a hint must be visible to the tool the Inbox Workflow tells
     * authors to read, since each of those now has its own node predicate.
     */
    internal val CATEGORIES: List<Pair<String, (UiNode) -> String?>> = listOf(
        "🔤 Text" to { it.text },
        "🏷️ Desc" to { it.contentDescription },
        "🆔 IDs " to { it.viewIdResourceName },
        "🪟 Pane (hasPaneTitle)" to { it.paneTitle },
        "👆 ClickLabel (hasClickActionLabel)" to { it.clickActionLabel },
        "🎭 Role (hasRoleDescription)" to { it.roleDescription },
        "💬 Hint (hasHintText)" to { it.hintText },
    )

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
