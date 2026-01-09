package cloud.trotter.dashbuddy.test

import cloud.trotter.dashbuddy.services.accessibility.UiNode
import java.lang.reflect.Field

object LogToUiNodeParser {

    private val nodeRegex = Regex("""^(\s*)UiNode\((.*)\)$""")

    fun parseLog(logContent: String): UiNode? {
        val lines = logContent.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val rootLine = lines.first()
        val rootNode = parseLine(rootLine) ?: return null

        // Stack: Pair(IndentLevel, Node)
        val stack = ArrayDeque<Pair<Int, UiNode>>()
        stack.addFirst(0 to rootNode)

        for (i in 1 until lines.size) {
            val line = lines[i]
            val match = nodeRegex.find(line) ?: continue

            val indentSpaces = match.groupValues[1].length
            val indentLevel = indentSpaces / 2
            val node = parseLine(line) ?: continue

            // 1. Pop stack until we find the parent
            while (stack.isNotEmpty() && stack.first().first >= indentLevel) {
                stack.removeFirst()
            }

            // 2. Link Parent <-> Child
            val parent = stack.firstOrNull()?.second
            if (parent != null) {
                parent.children.add(node)

                // --- CRITICAL FIX: Set the parent reference using Reflection ---
                setParent(node, parent)
            }

            stack.addFirst(indentLevel to node)
        }

        return rootNode
    }

    // Helper to set immutable 'val parent' using reflection
    private fun setParent(child: UiNode, parent: UiNode) {
        try {
            val parentField: Field = UiNode::class.java.getDeclaredField("parent")
            parentField.isAccessible = true
            parentField.set(child, parent)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback: If reflection fails, tests relying on .parent will fail.
        }
    }

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
            originalNode = null,
            // parent = null // Parent is set later via reflection
        )
    }
}