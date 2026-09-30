package cloud.trotter.dashbuddy.state.effects

import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.TreeLimits
import cloud.trotter.dashbuddy.domain.pipeline.LabelHorizon
import cloud.trotter.dashbuddy.domain.pipeline.LabelNode
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.state.effects.UiInteractionHandler.Candidate
import cloud.trotter.dashbuddy.util.AccNodeUtils

/*
 * #1149 — strategy 2b, the label re-find, and its outcome rule: moved out of UiInteractionHandler
 * (review S10, principle 3). Pure move — behaviour and tests unchanged.
 */

internal class SemanticWindow(val result: SemanticSearch, val inActive: Boolean)

internal sealed interface SemanticOutcome {
    data class Use(val candidates: List<Candidate>) : SemanticOutcome
    data class Inconclusive(val hits: Int) : SemanticOutcome
    data class FallThrough(val incompleteWindows: Int) : SemanticOutcome
}

/**
 * #1149 review L1/N1 — THE 2b outcome rule, one owner, over the DECIDING set: the active platform
 * window's search when it produced ≥ 1 hit; otherwise EVERY scoped window's search (with all their
 * incompleteness) — the #788 rule "active contributes none → keep them all" (a small same-package
 * dialog active over the still-sliding receipt sheet must not hand the tap to frozen bounds).
 * With H = the deciding set's hits and I = "a window in it was incomplete" (walk cut, unreadable
 * child, or a vetoing region):
 *  - |H| = 0 → FALL THROUGH to strategy 3, whether or not I: nothing was found, so nothing can be
 *    wrong, and strategy 3 keeps its own gates (containment, nested abort, ranker, #788);
 *  - |H| ≥ 1 ∧ I → INCONCLUSIVE, abort: a hidden twin is possible;
 *  - otherwise USE H (≥ 2 of them are twins: abort, downstream (R8)).
 */
internal fun decideSemanticOutcome(deciding: List<SemanticWindow>): SemanticOutcome {
    val hits = deciding.sumOf { it.result.hits.size }
    if (hits == 0) return SemanticOutcome.FallThrough(deciding.count { it.result.incomplete })
    if (deciding.any { it.result.incomplete }) return SemanticOutcome.Inconclusive(hits)
    val out = mutableListOf<Candidate>()
    for (w in deciding) {
        val base = out.size
        for (hit in w.result.hits) out.add(
            Candidate(hit.node, w.inActive, semantic = true, ancestors = hit.ancestors.map { it + base }),
        )
    }
    return SemanticOutcome.Use(out)
}

/**
 * A node the 2b walk already fetched, as a [LabelNode] (review I7 + N8): its [slots] hold the children
 * the walk read; a slot the walk did not (or could not) read is null — unreadable to the horizon, so
 * scanning a candidate never issues a second fetch.
 */
internal class WalkNode(
    private val node: AccessibilityNodeInfo,
    override val foreign: Boolean,
    override val takesClick: Boolean,
    val slots: Array<WalkNode?>,
    /** P1: advertised children beyond the (budget-capped) [slots] — never allocated, never read. */
    override val unreadableChildren: Int = 0,
) : LabelNode {
    // P4: lazy, and a foreign node's text/description is never touched.
    override val ownLabels: List<String> by lazy { if (foreign) emptyList() else ownLabelsOf(node) }
    override fun children(): List<LabelNode?> = slots.asList()
}

/** One window's 2b result: its hits, and whether anything in it could not be read completely (#1149 L1). */
internal class SemanticSearch(val hits: List<WalkHit>, val incomplete: Boolean)

internal class SemanticHit(val node: AccessibilityNodeInfo, val pre: Int, val lastPre: Int)

/**
 * Strategy 2b (#1149): every same-package node of [root] that takes a click
 * ([AccNodeUtils.isActionClickable]), matches the bind's owner class (when it has one) and whose
 * COMPLETE label horizon is the ref's EXACT fingerprint ([NodeRef.fingerprintMatches] — no
 * superset, #1102 review constraint 1). No geometric entrance test. Hits record their nesting
 * (pre-order intervals), so a wrapper carrying its own copy of the labels around the row stays
 * visible to the caller's nested-abort rule.
 *
 * ONE pass (review I7): every child is fetched once, into a [WalkNode]; each candidate's horizon is
 * then [LabelHorizon.scan] over those already-fetched nodes (N8 — the same rule as bind time and
 * verification), so the budget counts real IPC once.
 *
 * Bounded (#1102 review constraints 2 + 3): at most [TreeLimits.MAX_TREE_DEPTH] deep and
 * [TreeLimits.MAX_TREE_NODES] child fetches per root, budgeted before the call, nulls included.
 * Returns the hits found AND whether the window is INCOMPLETE — a bound cut the walk, a child read
 * null, or a candidate's own horizon is incomplete with its visible labels still consistent
 * (I4b/J4). What that means for the tap is [decideSemanticOutcome]'s call (L1/N1).
 */
internal fun findNodeBySemantics(root: AccessibilityNodeInfo, ref: NodeRef, expectedPackage: String): SemanticSearch {
    var fetched = 0
    var preCounter = 0
    var incomplete = false
    var stopped = false
    val hits = ArrayList<SemanticHit>()
    // L2/N7: 2b filters on the bind's OWNER class (its fingerprint is the owner's). A null
    // ownerClassHint on a ref that reached 2b (hasExactFingerprint) means the owner HAD no class —
    // no filter; legacy refs never reach 2b (they are never complete), so there is no fallback.
    val ownerClass = ref.ownerClassHint
    fun visit(node: AccessibilityNodeInfo, depth: Int): WalkNode {
        val pre = preCounter++
        val count = node.childCount.coerceAtLeast(0)
        // P1: allocate at most what the remaining fetch budget could ever fill — a hostile childCount
        // (Int.MAX_VALUE) must not become an allocation in the side-effect worker. The remainder is
        // unreadable (and the window incomplete).
        val capacity = minOf(count, (TreeLimits.MAX_TREE_NODES - fetched).coerceAtLeast(0))
        val self = WalkNode(
            node, foreign = false, takesClick = AccNodeUtils.isActionClickable(node),
            slots = arrayOfNulls(capacity), unreadableChildren = count - capacity,
        )
        if (count > capacity) incomplete = true
        if (count > 0 && depth >= TreeLimits.MAX_TREE_DEPTH) {
            incomplete = true // a tree the mapper itself would have cut; the slots stay unreadable
        } else {
            for (i in 0 until capacity) {
                if (stopped || fetched >= TreeLimits.MAX_TREE_NODES) { incomplete = true; stopped = true; break }
                fetched++
                // An unreadable child may hide the real control (or its twin): incomplete (I4 → L1).
                val child = node.getChild(i) ?: run { incomplete = true; null } ?: continue
                if (child.packageName?.toString() != expectedPackage) {
                    self.slots[i] = WalkNode(child, foreign = true, takesClick = false, slots = emptyArray())
                    continue
                }
                self.slots[i] = visit(child, depth + 1)
            }
        }
        val classOk = ownerClass == null || node.className?.toString() == ownerClass
        if (classOk && self.takesClick) {
            val scan = LabelHorizon.scan(self)
            if (!scan.complete) {
                // I4b, refined by J4: an incomplete candidate marks the window incomplete ONLY if what
                // IS visible is still consistent with the fingerprint (visible hint set ⊆ the ref's) —
                // the unseen part could complete it into the control or its twin. A region already
                // carrying a label OUTSIDE the set can never be an exact match, so it does not count.
                if (ref.visibleConsistentWith(scan.labels)) incomplete = true
            } else if (ref.fingerprintMatches(scan.labels)) {
                hits.add(SemanticHit(node, pre, preCounter - 1))
            }
        }
        return self
    }
    visit(root, 0)
    hits.sortBy { it.pre }
    return SemanticSearch(hits.mapIndexed { i, h ->
        WalkHit(h.node, relaxed = false, ancestors = hits.indices.filter { j ->
            j != i && hits[j].pre < h.pre && h.pre <= hits[j].lastPre
        })
    }, incomplete)
}
