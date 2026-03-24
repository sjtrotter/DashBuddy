package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

object SnapshotScreenDiagnostics {

    fun printXRay(node: UiNode, breadcrumbs: List<String> = emptyList()) {
        // 1. Breadcrumbs
        if (breadcrumbs.isNotEmpty()) {
            println("     🍞 BREADCRUMBS: " + breadcrumbs.joinToString(" -> "))
        }

        // 2. Node Analysis
        val candidates = collectCandidates(node)

        if (candidates.isEmpty()) {
            println("     🔎 X-RAY: (Tree is empty or has no text/IDs)")
            return
        }

        println("     🔎 X-RAY (Top 5 items):")

        printCategory("🔤 Text", candidates.mapNotNull { it.text }.filter { it.isNotBlank() })
        printCategory(
            "🏷️ Desc",
            candidates.mapNotNull { it.contentDescription }.filter { it.isNotBlank() })
        printCategory(
            "🆔 IDs ",
            candidates.mapNotNull { it.viewIdResourceName }.filter { it.isNotBlank() })

        println("        (Use 'UnknownScreenAnalysisTest' for deep inspection)")
    }

    private fun printCategory(header: String, items: List<String>) {
        val distinct = items.distinct()

        if (distinct.isNotEmpty()) {
            println("        $header:")
            distinct.take(5).forEach { item ->
                // Clean vertical list, no truncation
                println("           • \"$item\"")
            }

            if (distinct.size > 5) {
                println("           ... (${distinct.size - 5} more)")
            }
        }
    }

    private fun collectCandidates(root: UiNode): List<UiNode> {
        val results = mutableListOf<UiNode>()
        fun walk(node: UiNode) {
            if (!node.text.isNullOrBlank() ||
                !node.contentDescription.isNullOrBlank() ||
                !node.viewIdResourceName.isNullOrBlank()
            ) {
                results.add(node)
            }
            node.children.forEach { walk(it) }
        }
        walk(root)
        return results
    }
}