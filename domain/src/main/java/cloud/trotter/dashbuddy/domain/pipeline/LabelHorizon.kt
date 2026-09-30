package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * #1149 review N8 — the node shape the ONE label-horizon rule reads. Implemented for a mapped [UiNode]
 * (bind time, [UiLabelNode]) and, in `:app`, for a live `AccessibilityNodeInfo` (fire time: the
 * post-refresh verification scan, and the strategy-2b walk's already-fetched nodes).
 */
/**
 * #1149 review R2 — the ONE per-string cap on third-party text (#590), shared by the mapper's ingestion
 * (`capText`) and the label horizon's normalization, so bind and fire see the same string: a 4 096-space
 * description followed by "Primary" is blank on BOTH sides, not blank at bind and "Primary" live.
 */
object UiTextBounds {
    const val MAX_TEXT_LENGTH = 4_096
    fun cap(s: String): String = if (s.length <= MAX_TEXT_LENGTH) s else s.take(MAX_TEXT_LENGTH)
}

interface LabelNode {
    /** This node's own text and contentDescription, raw (non-null), in that order — [LabelHorizon] caps and blank-filters them (R2). */
    val ownLabels: List<String>

    /** `UiNode.takesClick` / the live `takesClick()` — a descendant that does is its own control. */
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
    /**
     * #1149 review R2/T6 — THE own-label normalization: cap ([UiTextBounds.cap]), then drop blanks
     * (`hintKeyOrNull` trims later) — the mapper's exact order. Used by [scan] and by the executor's
     * matched-node evidence labels, so there is no second copy.
     */
    fun normalizeOwnLabels(raw: List<String>): List<String> =
        raw.map(UiTextBounds::cap).filter { it.isNotBlank() }

    fun scan(node: LabelNode): LabelScan {
        val labels = mutableListOf<String>()
        var fetched = 0
        var complete = true
        fun visit(n: LabelNode, depth: Int): Boolean {
            // R2: cap, THEN blank-filter (hintKeyOrNull trims later) — the mapper's exact order.
            labels.addAll(normalizeOwnLabels(n.ownLabels))
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
    override val ownLabels: List<String> get() = listOfNotNull(node.text, node.contentDescription)
    override val takesClick: Boolean get() = node.takesClick
    override val foreign: Boolean get() = node.foreignPackage
    override val unreadableChildren: Int get() = node.unreadableChildren
    // P8: a view that wraps on demand — the horizon touches at most LABEL_SCAN_NODES slots.
    override fun children(): List<LabelNode?> = object : AbstractList<LabelNode?>() {
        override val size: Int get() = node.children.size
        override fun get(index: Int): LabelNode? = UiLabelNode(node.children[index])
    }
}
