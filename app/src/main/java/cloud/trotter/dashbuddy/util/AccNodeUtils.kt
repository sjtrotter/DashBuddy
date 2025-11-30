package cloud.trotter.dashbuddy.util

import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.log.Logger as Log

/**
 * A utility object for performing actions on AccessibilityNodeInfo objects.
 */
object AccNodeUtils {

    private const val TAG = "AccNodeUtils"

    /**
     * Recursively extracts identifying structural info (class name, view ID) from nodes.
     */
    fun extractStructure(nodeInfo: AccessibilityNodeInfo?, builder: StringBuilder) {
        if (nodeInfo == null || !nodeInfo.isVisibleToUser) return

        builder.append(nodeInfo.className)
        builder.append(nodeInfo.viewIdResourceName)

        for (i in 0 until nodeInfo.childCount) {
            extractStructure(nodeInfo.getChild(i), builder)
        }
    }

    /**
     * Recursively extracts visible text from a node and its children.
     */
    fun extractTexts(nodeInfo: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (nodeInfo == null || !nodeInfo.isVisibleToUser) return

        nodeInfo.text?.let {
            if (it.isNotEmpty()) texts.add(it.toString().trim())
        }
        nodeInfo.contentDescription?.let {
            val desc = it.toString().trim()
            if (desc.isNotEmpty() && !texts.contains(desc)) {
                texts.add(desc)
            }
        }
        for (i in 0 until nodeInfo.childCount) {
            extractTexts(nodeInfo.getChild(i), texts)
        }
    }

    /**
     * Finds a node containing the exact specified text and performs a click action.
     * Uses the robust 'clickNode' strategy (Self -> Parent -> Sibling).
     */
    fun findAndClickNodeByText(
        searchStartNode: AccessibilityNodeInfo?,
        textToFind: String
    ): Boolean {
        if (searchStartNode == null) {
            Log.w(TAG, "Cannot perform click, searchStartNode is null.")
            return false
        }

        Log.d(TAG, "Searching for node with text: '$textToFind'")
        val foundNodes = searchStartNode.findAccessibilityNodeInfosByText(textToFind)

        if (foundNodes.isNullOrEmpty()) {
            Log.w(TAG, "Text '$textToFind' not found.")
            return false
        }

        // Use the first match found
        val targetNode = foundNodes[0]
        return clickNode(targetNode)
    }

    /**
     * ROBUST CLICK STRATEGY:
     * 1. Try the node itself.
     * 2. Try walking up the tree to find a clickable parent (Ancestor Strategy).
     * 3. Try walking the parent's other children to find a clickable sibling (Lateral Strategy).
     *
     * @param node The specific node we want to interact with.
     * @return `true` if a click action was successfully sent to *some* node.
     */
    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) {
            Log.w(TAG, "Cannot click: node is null.")
            return false
        }

        // Strategy 1 & 2: Self or Parent
        val clickableAncestor = findClickableCandidate(node)
        if (clickableAncestor != null) {
            Log.i(TAG, "Clicking ancestor/self (Class: ${clickableAncestor.className})")
            return clickableAncestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // Strategy 3: Lateral Search (Siblings)
        // If the text and the container aren't clickable, maybe there is an icon NEXT to the text.
        Log.d(TAG, "Ancestor click failed. Attempting lateral search (siblings)...")

        val parent = node.parent
        if (parent != null) {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChild(i) ?: continue

                // Don't check the node itself again, only siblings
                if (sibling != node && sibling.isClickable) {
                    Log.i(TAG, "Found clickable sibling! (Class: ${sibling.className}). Clicking.")
                    return sibling.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }

        Log.w(TAG, "Failed to find any clickable candidate (Self, Ancestor, or Sibling).")
        return false
    }

    /**
     * Traverses up from the given node to find the first ancestor (or the node itself)
     * that is clickable.
     */
    private fun findClickableCandidate(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var currentNode = node
        while (currentNode != null) {
            if (currentNode.isClickable) {
                return currentNode
            }
            currentNode = currentNode.parent
        }
        return null
    }
}