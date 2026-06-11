package cloud.trotter.dashbuddy.test

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

object LogToUiNodeParser {

    private val nodeRegex = Regex("""^(\s*)UiNode\((.*)\)$""")

    /** Mutable scaffolding while parsing; materialized into the immutable tree at the end (#363). */
    private class Scaffold(val proto: UiNode, val kids: MutableList<Scaffold> = mutableListOf())

    fun parseLog(logContent: String): UiNode? {
        val lines = logContent.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val root = parseLine(lines.first())?.let(::Scaffold) ?: return null

        // Stack: Pair(IndentLevel, Scaffold)
        val stack = ArrayDeque<Pair<Int, Scaffold>>()
        stack.addFirst(0 to root)

        for (i in 1 until lines.size) {
            val line = lines[i]
            val match = nodeRegex.find(line) ?: continue

            val indentSpaces = match.groupValues[1].length
            val indentLevel = indentSpaces / 2
            val node = parseLine(line)?.let(::Scaffold) ?: continue

            // 1. Pop stack until we find the parent
            while (stack.isNotEmpty() && stack.first().first >= indentLevel) {
                stack.removeFirst()
            }

            // 2. Link parent <- child in the scaffolding
            stack.firstOrNull()?.second?.kids?.add(node)

            stack.addFirst(indentLevel to node)
        }

        return materialize(root).restoreParents()
    }

    private fun materialize(s: Scaffold): UiNode =
        s.proto.copy(children = s.kids.map(::materialize))

    private fun parseLine(line: String): UiNode? {
        val match = nodeRegex.find(line) ?: return null
        val attributesStr = match.groupValues[2]

        var text: String? = null
        var desc: String? = null
        var className: String? = null
        var resourceId: String? = null

        // Robust attribute parsing handling commas inside quotes
        // We use a regex to split by ", " only if not inside single quotes
        val attrRegex = Regex("""(\w+)=('.*?'|[^,]+)(?:,\s*|$)""")

        attrRegex.findAll(attributesStr).forEach { matchResult ->
            val key = matchResult.groupValues[1]
            val value = matchResult.groupValues[2]

            when (key) {
                "text" -> text = value.removeSurrounding("'")
                "desc" -> desc = value.removeSurrounding("'")
                "class" -> className = value
                "id" -> resourceId = value
            }
        }

        return UiNode(
            text = text,
            contentDescription = desc,
            viewIdResourceName = resourceId,
            className = className,
            // parent = null // Parent is set later via reflection
        )
    }
}