package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.domain.action.TargetExpectation
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toBoundingBox
import cloud.trotter.dashbuddy.util.AccNodeUtils
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes app-owned `RuleAction` taps on the platform app (#425).
 *
 * The target comes from the (untrusted, future-CDN #192) ruleset as a
 * [NodeRef] fingerprint, so the tap is verified at fire time against
 * app-owned anchors the ruleset cannot influence:
 *
 * 1. **Package scope** — only windows belonging to the platform's package are
 *    searched. No expected package → no tap (fail closed).
 * 2. **Label expectation** — the resolved node's subtree texts must satisfy
 *    the action's [TargetExpectation] (e.g. DECLINE_OFFER only taps a node
 *    labeled "Decline"). Platform buttons usually label via a child TextView,
 *    so collection walks a bounded subtree.
 * 3. **Strict click** — self-or-ancestor only. The old clickable-*sibling*
 *    fallback is deliberately absent here: the verified node's sibling can be
 *    the opposite button (Accept sits beside Decline in the offer footer).
 *
 * Any check failing skips the tap and logs — the user acts manually instead.
 */
@Singleton
class UiInteractionHandler @Inject constructor(
    private val accessibilitySource: AccessibilitySource
) {

    companion object {
        /** Max subtree depth scanned when collecting a candidate's labels. */
        private const val LABEL_SCAN_DEPTH = 3

        /** Max nodes visited per label scan — bounded ingestion of third-party UI. */
        private const val LABEL_SCAN_NODES = 24
    }

    /**
     * Re-resolve [ref] in the live tree (scoped to [expectedPackage]), verify
     * the node against [expectation], and click it.
     *
     * @return true if a click action was dispatched to a verified node.
     */
    fun performVerifiedClick(
        ref: NodeRef,
        expectedPackage: String?,
        expectation: TargetExpectation,
        description: String,
    ): Boolean {
        Timber.i("UiInteractionHandler: attempting verified click (%s)", description)

        if (expectedPackage.isNullOrEmpty()) {
            Timber.w("No package scope for %s — refusing to click (fail closed)", description)
            return false
        }
        val roots = accessibilitySource.getLiveWindowRoots()
            .filter { it.packageName?.toString() == expectedPackage }
        if (roots.isEmpty()) {
            Timber.w("No live windows for package %s — cannot click (%s)", expectedPackage, description)
            return false
        }

        val candidates = findCandidates(roots, ref)
        if (candidates.isEmpty()) {
            Timber.w(
                "Could not find any live node for: %s (id=%s, text=%s, bounds=%s)",
                description, ref.viewIdSuffix, ref.text, ref.boundsInScreen,
            )
            return false
        }

        val verified = candidates.filter { expectation.matchesLabels(collectLabels(it)) }
        if (verified.isEmpty()) {
            Timber.w(
                "%d candidate(s) for %s but NONE passed label verification (%s) — refusing to click",
                candidates.size, description, expectation.labelPattern,
            )
            return false
        }

        // Disambiguate: prefer the exact stored-bounds match, else first verified.
        val exactMatch = verified.find { node ->
            val liveBounds = Rect()
            node.getBoundsInScreen(liveBounds)
            liveBounds.toBoundingBox() == ref.boundsInScreen
        }
        val target = exactMatch ?: verified.first()
        if (exactMatch == null && verified.size > 1) {
            Timber.w(
                "No exact bounds match among %d verified candidates for: %s — clicking first",
                verified.size, description,
            )
        }
        return AccNodeUtils.clickNodeStrict(target)
    }

    /**
     * Search the scoped roots, strongest strategy first (so a weak bounds
     * match in one window can't beat a viewId match in another). The target
     * may live in a window other than the active one — e.g. DoorDash's offer
     * while the bubble holds focus.
     */
    private fun findCandidates(
        roots: List<AccessibilityNodeInfo>,
        ref: NodeRef,
    ): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        // Strategy 1: find by view ID
        val targetId = ref.viewIdSuffix
        if (!targetId.isNullOrEmpty()) {
            for (root in roots) candidates.addAll(root.findAccessibilityNodeInfosByViewId(targetId))
        }
        // Strategy 2: find by text
        val targetText = ref.text
        if (candidates.isEmpty() && !targetText.isNullOrEmpty()) {
            for (root in roots) candidates.addAll(root.findAccessibilityNodeInfosByText(targetText))
        }
        // Strategy 3: walk each tree matching by bounds + className
        if (candidates.isEmpty()) {
            for (root in roots) findNodeByBounds(root, ref.boundsInScreen, ref.classNameHint, candidates)
        }
        return candidates
    }

    /**
     * Collect the candidate's own text/contentDescription plus its bounded
     * subtree's — platform buttons typically carry their label on a child
     * TextView (e.g. DoorDash's `textView_prism_button_title`).
     */
    private fun collectLabels(node: AccessibilityNodeInfo): List<String> {
        val labels = mutableListOf<String>()
        var visited = 0
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > LABEL_SCAN_DEPTH || visited >= LABEL_SCAN_NODES) return
            visited++
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }
        visit(node, 0)
        return labels
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
