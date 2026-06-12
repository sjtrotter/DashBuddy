package cloud.trotter.dashbuddy.util

import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * A utility object for performing actions on AccessibilityNodeInfo objects.
 */
object AccNodeUtils {

    /**
     * STRICT CLICK (#425): self-or-ancestor only — no sibling fallback.
     *
     * Used for verified `RuleAction` taps, where label verification ran on
     * *this* node's subtree: a clickable sibling can be the opposite control
     * (Accept sits beside Decline in the offer footer), so falling laterally
     * would tap something the verification never looked at.
     */
    fun clickNodeStrict(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) {
            Timber.w("Cannot click: node is null.")
            return false
        }
        val clickable = findClickableCandidate(node)
        if (clickable == null) {
            Timber.w("Strict click: no clickable self/ancestor — refusing (no sibling fallback).")
            return false
        }
        if (clickable != node) {
            Timber.i("Strict click: delegating to clickable ancestor (Class: ${clickable.className})")
        }
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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