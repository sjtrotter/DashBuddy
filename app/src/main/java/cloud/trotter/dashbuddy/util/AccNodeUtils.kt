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
     * This helps differentiate screens with identical text but different layouts.
     */
    fun extractStructure(nodeInfo: AccessibilityNodeInfo?, builder: StringBuilder) {
        if (nodeInfo == null || !nodeInfo.isVisibleToUser) return

        builder.append(nodeInfo.className)
        builder.append(nodeInfo.viewIdResourceName)
        // Add other stable attributes if needed (e.g., isClickable)
        // builder.append(nodeInfo.isClickable)

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
     * Finds a node containing the exact specified text and performs a click action on it
     * or its first clickable parent.
     *
     * @param searchStartNode The node to start the search from. For best results, this should be
     * the root node of the window from `getRootInActiveWindow()`.
     * @param textToFind The exact text of the node to be clicked.
     * @return `true` if the node was found and the click action was successfully performed, `false` otherwise.
     */
    fun findAndClickNodeByText(
        searchStartNode: AccessibilityNodeInfo?,
        textToFind: String
    ): Boolean {
        if (searchStartNode == null) {
            Log.w(TAG, "Cannot perform click, the provided searchStartNode is null.")
            return false
        }

        Log.d(TAG, "Searching for node with exact text: '$textToFind'")

        // Use the built-in, optimized find method instead of custom recursion.
        val foundNodes = searchStartNode.findAccessibilityNodeInfosByText(textToFind)

        if (foundNodes.isNullOrEmpty()) {
            Log.w(
                TAG,
                "Node with text '$textToFind' not found in the provided search node's hierarchy."
            )
            return false
        }

        // Use the first node found.
        val targetNode = foundNodes[0]
        Log.d(TAG, "Node with text found. Finding its clickable ancestor...")
        val clickableNode = findClickableParent(targetNode)

        if (clickableNode != null) {
            Log.i(
                TAG,
                "Performing CLICK action on node for text: '$textToFind' (Clickable parent: ${clickableNode.className})"
            )
            val success = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!success) {
                Log.e(TAG, "performAction(ACTION_CLICK) failed for node with text: '$textToFind'")
            }
            // It's good practice to recycle nodes you obtain from a find... call.
            return success
        } else {
            Log.w(
                TAG,
                "Node with text '$textToFind' was found, but neither it nor its parents are clickable."
            )
            return false
        }
    }

    /**
     * Traverses up from the given node to find the first ancestor (or the node itself)
     * that is clickable.
     *
     * @param node The starting node.
     * @return The clickable node, or null if none are found up to the root.
     */
    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var currentNode = node
        while (currentNode != null) {
            if (currentNode.isClickable) {
                return currentNode
            }
            // Important: We are just getting a reference, not creating a new node,
            // so we don't recycle the original 'node' parameter here.
            currentNode = currentNode.parent
        }
        return null // No clickable parent found
    }
}
