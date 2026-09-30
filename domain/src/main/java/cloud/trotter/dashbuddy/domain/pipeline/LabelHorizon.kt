package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * #1149 review N8 — the node shape the ONE label-horizon rule reads. Implemented for a mapped [UiNode]
 * (bind time, [UiLabelNode]) and, in `:app`, for a live `AccessibilityNodeInfo` (fire time: the
 * post-refresh verification scan, and the strategy-2b walk's already-fetched nodes).
 */
interface LabelNode {
    /** This node's own non-blank text and contentDescription, in that order. */
    val ownLabels: List<String>

    /** `UiNode.takesClick` / `AccNodeUtils.isActionClickable` — a descendant that does is its own control. */
    val takesClick: Boolean

    /** Belongs to another package than the scanned window: spends its slot, never read. */
    val foreign: Boolean

    /** Advertised children known to be unreadable beyond [children]'s null entries (a mapped tree's drops). */
    val unreadableChildren: Int

    /**
     * The child slots, in order; a null entry is an unreadable slot. A live implementation fetches on
     * `get(i)`, so [LabelHorizon.scan] budgets each slot BEFORE touching it.
     */
    fun children(): List<LabelNode?>
}

/** A bounded label scan's result; [complete] = no in-horizon label went unseen. */
data class LabelScan(val labels: List<String>, val complete: Boolean)

/**
 * #1149 review N8 — THE label-horizon rule, one owner, used by bind time (`NodeRef.hintLabelsOf`) and
 * fire time (the 2b walk's per-node region and the post-refresh verification scan): the node's own
 * labels, then its children depth-first in pre-order down to [NodeRef.LABEL_SCAN_DEPTH], at most
 * [NodeRef.LABEL_SCAN_NODES] child slots (each budgeted before it is touched, an unreadable one
 * included), never reading a [LabelNode.foreign] child and never descending into one that
 * [LabelNode.takesClick] (its labels are its own control's, I3).
 *
 * [LabelScan.complete] is false when the slot cap cut it, a slot was unreadable, or an in-horizon node
 * reports [LabelNode.unreadableChildren]. The depth bound is the HORIZON, not incompleteness (the
 * I2 × I4b vet decision): labels below it belong to neither side's fingerprint.
 */
object LabelHorizon {
    fun scan(node: LabelNode): LabelScan {
        val labels = mutableListOf<String>()
        var fetched = 0
        var complete = true
        fun visit(n: LabelNode, depth: Int): Boolean {
            labels.addAll(n.ownLabels)
            if (depth >= NodeRef.LABEL_SCAN_DEPTH) return true // the horizon, not a cut
            if (n.unreadableChildren > 0) complete = false
            val kids = n.children()
            for (i in kids.indices) {
                if (fetched >= NodeRef.LABEL_SCAN_NODES) { complete = false; return false }
                fetched++
                val child = kids[i]
                if (child == null) { complete = false; continue }
                if (child.foreign) continue
                if (child.takesClick) continue
                if (!visit(child, depth + 1)) return false
            }
            return true
        }
        visit(node, 0)
        return LabelScan(labels, complete)
    }
}

/** The bind-time [LabelNode]: a mapped [UiNode] (foreign / unreadable stamped by the mapper, L3/L4/N6). */
class UiLabelNode(private val node: UiNode) : LabelNode {
    override val ownLabels: List<String> get() = listOfNotNull(
        node.text?.takeIf { it.isNotBlank() },
        node.contentDescription?.takeIf { it.isNotBlank() },
    )
    override val takesClick: Boolean get() = node.takesClick
    override val foreign: Boolean get() = node.foreignPackage
    override val unreadableChildren: Int get() = node.unreadableChildren
    override fun children(): List<LabelNode?> = node.children.map(::UiLabelNode)
}
