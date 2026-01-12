package cloud.trotter.dashbuddy.services.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi

/**
 * A data class to hold structured information about a single UI element (node).
 * Used to transcribe [AccessibilityNodeInfo] objects into a useful data structure
 * for use within the DashBuddy app.
 */
data class UiNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val stateDescription: String? = null,
    val viewIdResourceName: String? = null, // e.g., "com.doordash.driverapp:id/accept_button"
    val className: String? = null,           // e.g., "android.widget.Button"
    val isClickable: Boolean = false,
    val isEnabled: Boolean = false,
    val isChecked: Int = 0,
    val boundsInScreen: Rect = Rect(),
    val parent: UiNode? = null, // Can be useful for hierarchy
    val children: MutableList<UiNode> = mutableListOf(),
    val originalNode: AccessibilityNodeInfo? = null,
) {
    /**
     * This method is for debugging. It gets called automatically by Log.d()
     * to convert the whole tree into a nicely indented, readable string.
     * It is NOT part of the tree creation logic.
     */
    override fun toString(): String {
        val builder = StringBuilder()
        appendNode(builder, this, 0)
        return builder.toString()
    }

    /**
     * A private helper used by toString() to handle the recursion and indentation
     * for pretty-printing the tree structure.
     */
    private fun appendNode(builder: StringBuilder, node: UiNode, indent: Int) {
        val indentation = "  ".repeat(indent)
        val id = node.viewIdResourceName?.substringAfter("id/") ?: "no_id"
        val desc = node.contentDescription?.let { "desc='$it'" } ?: ""
        val txt = node.text?.let { "text='$it'" } ?: ""
        val state = node.stateDescription?.let { "state='$it'" }
        val identifier = listOf(txt, desc).filter { it.isNotEmpty() }.joinToString(", ")

        builder.append(indentation)
            .append("UiNode($identifier, id=$id, state=${state}, class=${node.className})\n")

        for (child in node.children) {
            appendNode(builder, child, indent + 1)
        }
    }

    /**
     * Traverses the entire UiNode tree and returns the first node that matches the predicate.
     * @param predicate A function that returns true if the node is a match.
     * @return The first matching UiNode, or null if no match is found.
     */
    fun findNode(predicate: (UiNode) -> Boolean): UiNode? {
        if (predicate(this)) {
            return this
        }
        for (child in children) {
            val found = child.findNode(predicate)
            if (found != null) {
                return found
            }
        }
        return null
    }

    /**
     * Traverses the entire UiNode tree and returns a list of all nodes that match the predicate.
     * @param predicate A function that returns true if the node is a match.
     * @return A List of matching UiNodes.
     */
    fun findNodes(predicate: (UiNode) -> Boolean): List<UiNode> {
        val matches = mutableListOf<UiNode>()
        if (predicate(this)) {
            matches.add(this)
        }
        for (child in children) {
            matches.addAll(child.findNodes(predicate))
        }
        return matches
    }

    /**
     * A convenience function to quickly check if at least one node in the tree matches the predicate.
     * @return True if a matching node is found, false otherwise.
     */
    fun hasNode(predicate: (UiNode) -> Boolean): Boolean {
        return this.findNode(predicate) != null
    }

    /**
     * Generates a hash code based ONLY on the structural identity of the node and its children.
     * It ignores mutable text/descriptions but respects ClassName, ID, and Hierarchy.
     * Use this to detect if the "Layout" has changed.
     */
    fun getStructuralHashCode(): Int {
        var result = className?.hashCode() ?: 0
        result = 31 * result + (viewIdResourceName?.hashCode() ?: 0)
        // We intentionally IGNORE text, contentDescription, etc. for the structural hash

        // Recursively add children structure
        for (child in children) {
            result = 31 * result + child.getStructuralHashCode()
        }
        return result
    }

    /** * Generates a hash code based on Content (Text).
     * Use this to detect if the "Data" on the screen has changed.
     */
    fun getContentHashCode(): Int {
        var result = text?.hashCode() ?: 0
        result = 31 * result + (contentDescription?.hashCode() ?: 0)
        for (child in children) {
            result = 31 * result + child.getContentHashCode()
        }
        return result
    }

    val allText: List<String> by lazy {
        val results = mutableListOf<String>()
        collectText(this, results)
        results
    }

    private fun collectText(node: UiNode, list: MutableList<String>) {
        // Add current node's text
        if (!node.text.isNullOrBlank()) list.add(node.text)
        if (!node.contentDescription.isNullOrBlank()) list.add(node.contentDescription)

        // Recursively add children's text
        node.children.forEach { collectText(it, list) }
    }

    companion object {
        /**
         * This is the factory method that:
         * 1. Creates a UiNode for the given AccessibilityNodeInfo.
         * 2. Recursively calls itself to process all children.
         */
        @RequiresApi(Build.VERSION_CODES.BAKLAVA)
        fun from(accNode: AccessibilityNodeInfo?, parentUiNode: UiNode? = null): UiNode? {
            if (accNode == null) return null

            val bounds = Rect()
            accNode.getBoundsInScreen(bounds)

            // Step 1: Get the info for this node and create the UiNode object.
            val currentUiNode = UiNode(
                text = accNode.text?.toString(),
                contentDescription = accNode.contentDescription?.toString(),
                stateDescription = accNode.stateDescription?.toString(),
                viewIdResourceName = accNode.viewIdResourceName,
                className = accNode.className?.toString(),
                isClickable = accNode.isClickable,
                isEnabled = accNode.isEnabled,
                isChecked = accNode.checked,
                boundsInScreen = bounds,
                parent = parentUiNode,
                originalNode = accNode
            )

            // Step 2: Get the children and do this recursively.
            for (i in 0 until accNode.childCount) {
                val childAccNode = accNode.getChild(i)
                from(childAccNode, currentUiNode)?.let { childUiNode ->
                    currentUiNode.children.add(childUiNode)
                }
            }

            return currentUiNode
        }
    }
}