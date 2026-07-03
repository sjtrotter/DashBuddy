package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #600 — evidence-ranked click disambiguation.
 *
 * Exact-bounds `==` pinning died to temporal drift (an animating
 * `BottomSheetModal` moves between recognition and re-resolve), so the
 * disambiguator needs a ranking that survives drift and rejects a
 * same-view-id stale sibling. See [ClickCandidateRanker]'s KDoc for the full
 * grounding.
 */
class ClickCandidateRankerTest {

    private fun facts(text: String?, bounds: BoundingBox, labels: List<String> = emptyList()) =
        ClickCandidateRanker.CandidateFacts(text = text, labels = labels, bounds = bounds)

    @Test
    fun `T1 exact stored text wins over a same-view-id stale sibling (real field case)`() {
        // The pinned ref is the confirm sheet's "Decline offer" button. The live
        // tie-break pool has TWO nodes sharing the underlying offer popup's view id
        // (textView_prism_button_title): the STALE offer-popup "Decline" title, and
        // the live confirm-sheet "Decline offer" title — whose bounds have drifted
        // (animating BottomSheetModal) so exact-bounds `==` fails on both.
        val ref = NodeRef(
            viewIdSuffix = "textView_prism_button_title",
            text = "Decline offer",
            classNameHint = "android.widget.TextView",
            boundsInScreen = BoundingBox(0, 900, 400, 950),
            pathFingerprint = "fp",
        )
        val staleOfferPopupTitle = facts(text = "Decline", bounds = BoundingBox(0, 500, 400, 550))
        val driftedConfirmSheetTitle = facts(text = "Decline offer", bounds = BoundingBox(0, 916, 400, 966))

        val result = ClickCandidateRanker.rank(ref, listOf(staleOfferPopupTitle, driftedConfirmSheetTitle))

        assertEquals(1, result.index)
        assertEquals(ClickCandidateRanker.Tier.EXACT_TEXT, result.tier)
    }

    @Test
    fun `T2 picks the max bounds-overlap candidate when text is absent or tied (drift-only case)`() {
        val ref = NodeRef(
            viewIdSuffix = null,
            text = null,
            classNameHint = null,
            boundsInScreen = BoundingBox(0, 100, 100, 150),
            pathFingerprint = "fp",
        )
        val farAway = facts(text = null, bounds = BoundingBox(500, 500, 600, 550))
        val slightlyDrifted = facts(text = null, bounds = BoundingBox(10, 110, 100, 150))

        val result = ClickCandidateRanker.rank(ref, listOf(farAway, slightlyDrifted))

        assertEquals(1, result.index)
        assertEquals(ClickCandidateRanker.Tier.BOUNDS_OVERLAP, result.tier)
    }

    @Test
    fun `exact-bounds match is IoU 1point0 and still wins via T2`() {
        val bounds = BoundingBox(0, 100, 200, 150)
        val ref = NodeRef(
            viewIdSuffix = null,
            text = null,
            classNameHint = null,
            boundsInScreen = bounds,
            pathFingerprint = "fp",
        )
        val exactBounds = facts(text = null, bounds = bounds)
        val partialOverlap = facts(text = null, bounds = BoundingBox(0, 120, 200, 170))

        val result = ClickCandidateRanker.rank(ref, listOf(partialOverlap, exactBounds))

        assertEquals(1, result.index)
        assertEquals(ClickCandidateRanker.Tier.BOUNDS_OVERLAP, result.tier)
    }

    @Test
    fun `degenerate tie with no overlap and no text match flags UNRESOLVED and picks first`() {
        val ref = NodeRef(
            viewIdSuffix = null,
            text = "Confirm",
            classNameHint = null,
            boundsInScreen = BoundingBox(0, 0, 50, 50),
            pathFingerprint = "fp",
        )
        val a = facts(text = "Something else", bounds = BoundingBox(500, 500, 550, 550))
        val b = facts(text = "Other", bounds = BoundingBox(700, 700, 750, 750))

        val result = ClickCandidateRanker.rank(ref, listOf(a, b))

        assertEquals(0, result.index)
        assertEquals(ClickCandidateRanker.Tier.UNRESOLVED, result.tier)
    }
}
