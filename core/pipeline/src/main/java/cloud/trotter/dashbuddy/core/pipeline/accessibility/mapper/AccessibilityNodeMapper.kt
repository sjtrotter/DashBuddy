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

    /**
     * True once the NODE budget is spent — no node can be admitted at ANY depth
     * from here on. Distinct from [truncated], which also flips when a single deep
     * branch hits the depth cap (a local cut that leaves shallower siblings
     * admissible). The mapper's breadth short-circuit keys on THIS so a deep branch
     * never suppresses an admissible sibling, but an exhausted budget stops all
     * further getChild() IPC.
     */
    val nodesExhausted: Boolean
        get() = nodes >= maxNodes

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

        /**
         * Per-string ingestion cap (#590). Third-party text is untrusted: a node
         * reporting a 100 000-char `text` would ride verbatim into the [UiNode] and
         * the serialized capture envelope. Legitimate screen text nodes (labels,
         * addresses, instruction bodies) never approach 4 KiB; the cap truncates a
         * pathological string while leaving every real screen untouched. Applied to
         * `text`, `contentDescription`, and `stateDescription` at conversion.
         */
        const val MAX_TEXT_LENGTH = 4_096
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

/**
 * Text-length cap (#590): a pathological node text can't ride verbatim into the
 * [UiNode] / capture envelope. `take` is safe on any String and a no-op below the
 * cap, so every real screen string is untouched.
 */
private fun String.capText(): String =
    if (length <= TreeBudget.MAX_TEXT_LENGTH) this else take(TreeBudget.MAX_TEXT_LENGTH)

private fun convert(
    node: AccessibilityNodeInfo,
    depth: Int,
    budget: TreeBudget,
): UiNode? {
    if (!budget.admit(depth)) return null

    val childCount = node.childCount
    var nullChildren = 0
    val children = ArrayList<UiNode>(childCount.coerceAtMost(TreeBudget.MAX_TREE_NODES))
    for (i in 0 until childCount) {
        // Breadth short-circuit (#590): once the NODE budget is exhausted, no further
        // child can be admitted, so stop issuing getChild() binder IPC. Without this
        // a node reporting a hostile childCount (e.g. 50 000, or Int.MAX_VALUE) drives
        // one IPC per reported child even though the tree is already full. Keyed on
        // nodesExhausted (not truncated) so a deep branch hitting the depth cap does
        // NOT suppress this node's still-admissible shallower siblings.
        if (budget.nodesExhausted) break

        val childAccNode = node.getChild(i)
        if (childAccNode != null) {
            convert(childAccNode, depth + 1, budget)?.let(children::add)
        } else {
            nullChildren++
        }
    }

    // In debug builds, log when children are reported but inaccessible. #551 P7: this is benign,
    // frequent per-frame tree noise — VERBOSE (firehose only), not WARN. WARN must mean a defended
    // invariant fired, and this noise was drowning the real WARNs (grace commits, gate denials).
    if (BuildConfig.DEBUG && nullChildren > 0) {
        Timber.tag("Mapper").v(
            "👻 NULL CHILDREN: %d/%d null at depth=%d class=%s id=%s",
            nullChildren, childCount, depth,
            node.className, node.viewIdResourceName,
        )
    }

    val bounds = Rect()
    node.getBoundsInScreen(bounds)

    return UiNode(
        text = node.text?.toString()?.capText(),
        contentDescription = node.contentDescription?.toString()?.capText(),
        stateDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()?.capText()
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
