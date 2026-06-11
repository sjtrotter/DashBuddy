package cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.core.pipeline.BuildConfig
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import timber.log.Timber

/**
 * Per-tree ingestion budget (#363). Third-party trees are untrusted input:
 * every `getChild(i)` is a binder IPC and the converted tree is serialized
 * into the capture envelope, so a pathological/redesigned target UI must not
 * be able to stack-overflow the accessibility service or balloon captures.
 *
 * Limits carry ~2×/10× margin over the golden corpus (max depth 27, max
 * nodes 327). Truncation is LOUD — one warning per tree, never silent.
 */
internal class TreeBudget(
    private val maxDepth: Int = MAX_TREE_DEPTH,
    private val maxNodes: Int = MAX_TREE_NODES,
) {
    private var nodes = 0
    var truncated = false
        private set

    /** True if a node at [depth] may be ingested; flags truncation otherwise. */
    fun admit(depth: Int): Boolean {
        if (depth > maxDepth || nodes >= maxNodes) {
            truncated = true
            return false
        }
        nodes++
        return true
    }

    fun logIfTruncated(rootClassName: CharSequence?) {
        if (truncated) {
            Timber.w(
                "Tree ingestion truncated (maxDepth=%d, maxNodes=%d) — root class=%s. " +
                    "Captured subtree is partial.",
                maxDepth, maxNodes, rootClassName,
            )
        }
    }

    companion object {
        const val MAX_TREE_DEPTH = 60
        const val MAX_TREE_NODES = 4_000
    }
}

/**
 * Convert an Android accessibility node into the immutable [UiNode] tree
 * (#363): children are built bottom-up into a read-only list, parents are
 * wired once at the root, and ingestion is bounded by [TreeBudget].
 */
fun AccessibilityNodeInfo?.toUiNode(): UiNode? {
    if (this == null) return null
    val budget = TreeBudget()
    val root = convert(this, depth = 0, budget = budget) ?: return null
    budget.logIfTruncated(className)
    return root.restoreParents()
}

private fun convert(
    node: AccessibilityNodeInfo,
    depth: Int,
    budget: TreeBudget,
): UiNode? {
    if (!budget.admit(depth)) return null

    val childCount = node.childCount
    var nullChildren = 0
    val children = ArrayList<UiNode>(childCount)
    for (i in 0 until childCount) {
        val childAccNode = node.getChild(i)
        if (childAccNode != null) {
            convert(childAccNode, depth + 1, budget)?.let(children::add)
        } else {
            nullChildren++
        }
    }

    // In debug builds, log when children are reported but inaccessible
    if (BuildConfig.DEBUG && nullChildren > 0) {
        Timber.w(
            "👻 NULL CHILDREN: %d/%d null at depth=%d class=%s id=%s",
            nullChildren, childCount, depth,
            node.className, node.viewIdResourceName,
        )
    }

    val bounds = Rect()
    node.getBoundsInScreen(bounds)

    return UiNode(
        text = node.text?.toString(),
        contentDescription = node.contentDescription?.toString(),
        stateDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()
        } else null,
        viewIdResourceName = node.viewIdResourceName,
        className = node.className?.toString(),
        isClickable = node.isClickable,
        isEnabled = node.isEnabled,
        isChecked = node.checked,
        boundsInScreen = bounds.toBoundingBox(),
        children = children,
    )
}
