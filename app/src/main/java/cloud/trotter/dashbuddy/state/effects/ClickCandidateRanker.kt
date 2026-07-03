package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef

/**
 * Picks the live candidate that best matches a pinned [NodeRef], when more
 * than one label-verified node resolves for a click target (#600).
 *
 * ## Why exact-bounds pinning died
 * The original disambiguator compared `getBoundsInScreen()` for exact `==`
 * equality against the bounds captured at recognition time. That assumption
 * broke from TEMPORAL drift, not a coordinate-space bug: a field capture
 * showed the same confirm button at 4 different rects across snapshots,
 * because recognition can snapshot an ANIMATING `BottomSheetModal` — by the
 * time the tap re-resolves the tree (~600ms later in the field capture that
 * grounds this fix), the sheet has settled at a different position. Exact
 * `==` then matches *nothing*, so the old fallback ("clicking first" in
 * enumeration order) fired on effectively every automated click (~40+/week)
 * and happened to land right only by luck of enumeration order. Worse, the
 * tie-break pool itself can contain a *stale* node from an underlying window
 * that happens to share a view id with the real target — e.g. an offer
 * popup's "Decline" title and a confirm sheet's "Decline offer" title both
 * carry `textView_prism_button_title` — so "first" was never a safe fallback
 * ordering either.
 *
 * ## Ranking (lexicographic — first decisive tier wins)
 * - **[Tier.EXACT_TEXT]** — `ref.text` (already truncated to 50 chars at pin
 *   time, see [NodeRef.text] / `Ruleset.buildNodeRef`) equals a candidate's
 *   own text, truncated the same way, compared exactly and case-sensitively.
 *   Decisive because two controls in the same footer are very unlikely to
 *   carry the exact same label ("Decline offer" ≠ "Decline").
 * - **[Tier.BOUNDS_OVERLAP]** — the candidate with the greatest
 *   Intersection-over-Union against `ref.boundsInScreen`, as long as the IoU
 *   is greater than zero. An exact bounds match is just the IoU == 1.0 case,
 *   so this tier subsumes the old exact-match fast path without a separate
 *   branch — a small amount of drift still resolves correctly.
 * - **[Tier.UNRESOLVED]** — nothing above was decisive. Falls back to the
 *   first candidate (same behavior as before the fix), but the caller logs
 *   this at WARN — it is now the genuinely exceptional case, not the common
 *   one Principle 7 warns will drown the WARN channel.
 *
 * `pathFingerprint` (T3 in the #600 build plan) is intentionally NOT
 * implemented here. `Ruleset.buildNodeRef` computes it by walking the
 * *recognition-layer* `UiNode.parent`/`children` chain (index-in-parent is a
 * plain list lookup there); a live `AccessibilityNodeInfo` has no equivalent
 * cheap "index among my parent's children" query — matching a child back to
 * itself would mean comparing freshly-fetched, non-`equals`-safe node
 * instances one by one. Building that would mean either moving
 * `buildNodeRef` (out of scope per the #600 plan's vet amendments) or a
 * second, divergence-prone reimplementation of the same path-walk against a
 * different node type (a Principle-5 SSOT risk) — so T1/T2/T4 ship this PR
 * and T3 is left for a follow-up if the tie-break pool ever needs it.
 */
object ClickCandidateRanker {

    /** Which tier resolved the pick. [UNRESOLVED] is the only WARN-worthy outcome. */
    enum class Tier { EXACT_TEXT, BOUNDS_OVERLAP, UNRESOLVED }

    /**
     * Plain facts about one live candidate node, collected by the handler.
     * Never the node itself — keeps this ranker pure/testable without
     * Robolectric or a live accessibility tree. [labels] rides along for
     * WARN-level diagnostics on an [Tier.UNRESOLVED] tie; it plays no part in
     * the ranking decision itself.
     */
    data class CandidateFacts(
        val text: String?,
        val labels: List<String>,
        val bounds: BoundingBox,
    )

    /** The winning candidate's index into the input list, plus which tier decided it. */
    data class Ranked(val index: Int, val tier: Tier)

    /**
     * Ranks [candidates] against [ref] and returns the winner.
     *
     * @param candidates must be non-empty — the caller (the handler) already
     *   aborts on an empty label-verified list before reaching the ranker.
     */
    fun rank(ref: NodeRef, candidates: List<CandidateFacts>): Ranked {
        require(candidates.isNotEmpty()) { "ClickCandidateRanker.rank called with no candidates" }

        val refText = ref.text
        if (refText != null) {
            val exactTextIndex = candidates.indexOfFirst { it.text?.take(50) == refText }
            if (exactTextIndex >= 0) return Ranked(exactTextIndex, Tier.EXACT_TEXT)
        }

        var bestIndex = 0
        var bestIoU = 0.0
        for (i in candidates.indices) {
            val overlap = boundsIoU(ref.boundsInScreen, candidates[i].bounds)
            if (overlap > bestIoU) {
                bestIoU = overlap
                bestIndex = i
            }
        }
        if (bestIoU > 0.0) return Ranked(bestIndex, Tier.BOUNDS_OVERLAP)

        return Ranked(0, Tier.UNRESOLVED)
    }

    /** Intersection-over-Union of two [BoundingBox]es; 0.0 when they don't overlap at all. */
    private fun boundsIoU(a: BoundingBox, b: BoundingBox): Double {
        val ix1 = maxOf(a.left, b.left)
        val iy1 = maxOf(a.top, b.top)
        val ix2 = minOf(a.right, b.right)
        val iy2 = minOf(a.bottom, b.bottom)
        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0 || ih <= 0) return 0.0
        val intersection = (iw.toLong() * ih.toLong()).toDouble()

        val union = boxArea(a) + boxArea(b) - intersection
        return if (union <= 0.0) 0.0 else intersection / union
    }

    private fun boxArea(box: BoundingBox): Double {
        val w = (box.right - box.left).coerceAtLeast(0)
        val h = (box.bottom - box.top).coerceAtLeast(0)
        return (w.toLong() * h.toLong()).toDouble()
    }
}
