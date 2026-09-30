package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.takesClick
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.TreeLimits
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toBoundingBox
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.state.effects.UiInteractionHandler.Companion.RELAXED_BOUNDS_IOU
import cloud.trotter.dashbuddy.util.AccNodeUtils

/*
 * #1149 — strategy 3, the bounded geometric walk, and the walk-hit shape both walks share: moved out of
 * UiInteractionHandler (review S10). Pure move.
 */

/** A walk hit: [ancestors] are the indices (into the SAME `out` list) of hits this one is inside. */
internal data class WalkHit(val node: AccessibilityNodeInfo, val relaxed: Boolean, val ancestors: List<Int>)

/**
 * Strategy 3 (#1093 shape — review rounds 2 and 3). Every hit is bounds-derived and must still
 * carry the bind's label hints; the walk itself decides NOTHING about identity:
 *  - an EXACT class+rect match that is CLICKABLE is a hit, and the walk STILL descends — a
 *    clickable wrapper at the captured rect with the real row inside it must expose both, so
 *    the verification stage can see the nesting and abort (pruning here handed the tap to
 *    the wrapper);
 *  - an exact match that is NOT clickable is skipped and descended — the caller resolves its
 *    action owner (#1149), the nearest clickable ANCESTOR, so ranking a shell above its
 *    clickable child would tap something outside the row;
 *  - a clickable same-class node overlapping the rect by >= [RELAXED_BOUNDS_IOU] is a RELAXED
 *    hit, descended into. Nothing is dropped here: a descendant hit that later FAILS
 *    verification must not evict the row it sits in, and one that PASSES makes the pair
 *    undecidable — both are the verification stage's call, which is why each hit records the
 *    hits it is nested inside.
 * Runs only when strategy 2b found nothing (#1149).
 *
 * Bounded (#1149 review R5): the mapper's [TreeLimits] depth and fetch budget, each fetch counted
 * before the call (nulls included). Returns false when a bound CUT the walk — the caller then takes
 * no candidates from this root (fail closed to manual): a partial geometric walk could leave one
 * wrong survivor exactly like a partial label walk.
 */
internal fun findNodeByBounds(
    root: AccessibilityNodeInfo,
    targetBounds: BoundingBox,
    className: String?,
    out: MutableList<WalkHit>,
): Boolean {
    var fetched = 0
    var cut = false
    val path = ArrayList<Int>()
    fun visit(node: AccessibilityNodeInfo, depth: Int) {
        if (cut) return
        visitBounds(node, targetBounds, className, out, path) {
            val count = node.childCount.coerceAtLeast(0)
            if (count > 0 && depth >= TreeLimits.MAX_TREE_DEPTH) { cut = true; return@visitBounds }
            for (i in 0 until count) {
                if (fetched >= TreeLimits.MAX_TREE_NODES) { cut = true; return@visitBounds }
                fetched++
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
                if (cut) return@visitBounds
            }
        }
    }
    visit(root, 0)
    return !cut
}

internal inline fun visitBounds(
    node: AccessibilityNodeInfo,
    targetBounds: BoundingBox,
    className: String?,
    out: MutableList<WalkHit>,
    path: ArrayList<Int>,
    descend: () -> Unit,
) {
    val liveBounds = Rect()
    node.getBoundsInScreen(liveBounds)
    val live = liveBounds.toBoundingBox()
    val classOk = className == null || node.className?.toString() == className
    // #1149 review J7: the same clickability predicate as everywhere else — a Compose control that
    // only ADVERTISES ACTION_CLICK at the exact rect is otherwise invisible to the bounds walk.
    val hit = classOk && node.takesClick() && (
        live == targetBounds || ClickCandidateRanker.boundsIoU(live, targetBounds) >= RELAXED_BOUNDS_IOU
    )
    if (hit) {
        out.add(WalkHit(node, relaxed = live != targetBounds, ancestors = path.toList()))
        path.add(out.size - 1)
    }
    descend()
    if (hit) path.removeAt(path.size - 1)
}
