package cloud.trotter.dashbuddy.test

import cloud.trotter.dashbuddy.services.accessibility.UiNode

object LogToUiNodeParser {

    // Regex to capture indentation and the attributes inside UiNode(...)
    // Matches: "  UiNode(text='...', id=..., class=...)"
    private val nodeRegex = Regex("""^(\s*)UiNode\((.*)\)$""")

    fun parseLog(logContent: String): UiNode? {
        val lines = logContent.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val rootLine = lines.first()
        val rootNode = parseLine(rootLine) ?: return null

        // Stack to keep track of parents: Pair(IndentationLevel, Node)
        val stack = ArrayDeque<Pair<Int, UiNode>>()
        stack.addFirst(0 to rootNode)

        for (i in 1 until lines.size) {
            val line = lines[i]
            val match = nodeRegex.find(line) ?: continue

            val indentSpaces = match.groupValues[1].length
            val indentLevel = indentSpaces / 2 // Assuming 2 spaces per indent
            val node = parseLine(line) ?: continue

            // Pop stack until we find the parent (indent level < current)
            while (stack.isNotEmpty() && stack.first().first >= indentLevel) {
                stack.removeFirst()
            }

            val parent = stack.firstOrNull()?.second
            parent?.children?.add(node)
            // Note: We can't set node.parent because `val parent` is immutable in your data class
            // unless you change it to `var`. For parsers, children list is usually enough.

            stack.addFirst(indentLevel to node)
        }

        return rootNode
    }

    private fun parseLine(line: String): UiNode? {
        val match = nodeRegex.find(line) ?: return null
        val attributesStr = match.groupValues[2]
        // e.g. "text='So far', id=no_id, class=android.widget.TextView"

        var text: String? = null
        var desc: String? = null
        var className: String? = null
        var resourceId: String? = null

        // Naive parsing of the comma-separated attributes
        // (This might fail if text contains commas, but works for your log format)
        val attributes = attributesStr.split(", ")

        for (attr in attributes) {
            when {
                attr.startsWith("text='") -> text = attr.removePrefix("text='").removeSuffix("'")
                attr.startsWith("desc='") -> desc = attr.removePrefix("desc='").removeSuffix("'")
                attr.startsWith("class=") -> className = attr.removePrefix("class=")
                attr.startsWith("id=") -> resourceId = attr.removePrefix("id=")
            }
        }

        return UiNode(
            text = text,
            contentDescription = desc,
            viewIdResourceName = resourceId,
            className = className,
            originalNode = null // Null for tests!
        )
    }
}