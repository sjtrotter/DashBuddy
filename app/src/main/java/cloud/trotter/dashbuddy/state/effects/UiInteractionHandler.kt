package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toBoundingBox
import cloud.trotter.dashbuddy.util.AccNodeUtils
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiInteractionHandler @Inject constructor(
    private val accessibilitySource: AccessibilitySource
) {

    fun performClick(templateNode: UiNode, description: String) {
        Timber.i("UiInteractionHandler: Attempting click ($description)")

        val liveRoot = accessibilitySource.getLiveNativeRoot()
        if (liveRoot == null) {
            Timber.w("Failed to click: Live root window is null.")
            return
        }

        val targetId = templateNode.viewIdResourceName
        val targetText = templateNode.text
        val targetBounds = templateNode.boundsInScreen

        // Strategy 1: find by view ID
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        if (!targetId.isNullOrEmpty()) {
            candidates.addAll(liveRoot.findAccessibilityNodeInfosByViewId(targetId))
        }
        // Strategy 2: find by text
        if (candidates.isEmpty() && !targetText.isNullOrEmpty()) {
            candidates.addAll(liveRoot.findAccessibilityNodeInfosByText(targetText))
        }
        // Strategy 3: walk the tree matching by bounds + className
        if (candidates.isEmpty()) {
            findNodeByBounds(liveRoot, targetBounds, templateNode.className, candidates)
        }

        if (candidates.isEmpty()) {
            Timber.w("Could not find any live node for: %s (id=%s, text=%s, bounds=%s)",
                description, targetId, targetText, targetBounds)
            return
        }

        // Disambiguate: prefer exact bounds match, fall back to first candidate
        val exactMatch = candidates.find { node ->
            val liveBounds = Rect()
            node.getBoundsInScreen(liveBounds)
            liveBounds.toBoundingBox() == targetBounds
        }
        val target = exactMatch ?: candidates.first()
        if (exactMatch == null && candidates.size > 1) {
            Timber.w("No exact bounds match among %d candidates for: %s — clicking first",
                candidates.size, description)
        }
        AccNodeUtils.clickNode(target)
    }

    /**
     * Walk the accessibility tree looking for a node at the given bounds,
     * optionally matching className. Used when the node has no ID or text.
     */
    private fun findNodeByBounds(
        node: AccessibilityNodeInfo,
        targetBounds: BoundingBox,
        className: String?,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        val liveBounds = Rect()
        node.getBoundsInScreen(liveBounds)
        val matches = liveBounds.toBoundingBox() == targetBounds
            && (className == null || node.className?.toString() == className)
        if (matches) {
            out.add(node)
            return // no need to check children of a match
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByBounds(child, targetBounds, className, out)
        }
    }
}