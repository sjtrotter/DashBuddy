package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.TreeLimits
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toUiNode
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.domain.pipeline.UiTextBounds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * #1149 — the multi-window outcome rule: the deciding set (active window first, else every window), incomplete / unreadable / cut windows, and stale targets across windows. Driven through [UiInteractionHandler.performVerifiedClick] over mocked live trees
 * ([UiInteractionHandlerTapTestKit]); every refusal path has a test that goes red if its guard is removed.
 */
@RunWith(RobolectricTestRunner::class)
class UiInteractionHandlerWindowOutcomeTest : UiInteractionHandlerTapTestKit() {

    /** An incomplete background window does not veto a complete, exact hit on the active sheet. */
    @Test
    fun `an incomplete background window does not block the active window's row`() = runTest {
        val row = payRow(top = 1774 - 400)
        val active = windowRoot(row)
        val background = windowRoot(view())
        whenever(background.childCount).thenReturn(2) // slot 1 reads null → that window is incomplete
        assertTrue(expand(handler(listOf(active, background), active)))
        row.clicks(1)
    }

    /**
     * The same with the ACTIVE window incomplete and no hit in it: N1 widens the deciding set to every
     * window (the background row is a hit) while keeping the active window's incompleteness — |H| ≥ 1 ∧ I,
     * abort. No frozen-bounds guess either.
     */
    @Test
    fun `an incomplete active window aborts even when a background window holds the row`() = runTest {
        val active = windowRoot(view())
        whenever(active.childCount).thenReturn(2)
        val bgRow = payRow(top = 1774 - 400)
        val background = windowRoot(bgRow)
        assertFalse(expand(handler(listOf(active, background), active)))
        bgRow.neverClicked()
    }

    /** A RecyclerView with an unreadable child in a BACKGROUND window: the active window is complete → 2b clicks the row. */
    @Test
    fun `an unreadable recycler child in a background window does not stop the active row`() = runTest {
        val row = payRow(top = 1774 - 400)
        val active = windowRoot(row)
        val recycler = view(cls = "androidx.recyclerview.widget.RecyclerView", children = listOf(view()))
        whenever(recycler.childCount).thenReturn(2)
        val background = windowRoot(recycler)
        assertTrue(expand(handler(listOf(active, background), active)))
        row.clicks(1)
    }

    /**
     * Same window incomplete but NO exact 2b hit (the row carries one extra label): nothing was found, so
     * L1 falls through and strategy 3's containment check clicks the row at its captured rect.
     */
    @Test
    fun `an incomplete window with no 2b hit falls through to strategy 3`() = runTest {
        val row = view(clickable = true, bounds = rowRect, children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Expand"),
            view(cls = "android.widget.TextView", text = "Base pay"),
        ))
        val recycler = view(cls = "androidx.recyclerview.widget.RecyclerView", children = listOf(view()))
        whenever(recycler.childCount).thenReturn(2)
        assertTrue(expand(handler(windowRoot(recycler, row))))
        row.clicks(1)
    }

    /** An exact hit in an incomplete window: a hidden twin is possible — abort (no strategy-3 guess). */
    @Test
    fun `an exact hit in an incomplete window aborts`() = runTest {
        val row = payRow()
        val recycler = view(cls = "androidx.recyclerview.widget.RecyclerView", children = listOf(view()))
        whenever(recycler.childCount).thenReturn(2)
        assertFalse(expand(handler(windowRoot(recycler, row))))
        row.neverClicked()
    }

    /**
     * Bubble active (the active root is not a platform window) → the deciding set is EVERY scoped window,
     * so an incomplete second DoorDash window makes the one hit inconclusive — abort (see the report:
     * the round-3 brief's expected click contradicts its own rule; the rule is implemented).
     */
    @Test
    fun `with the bubble active an incomplete second platform window makes the hit inconclusive`() = runTest {
        val row = payRow(top = 1774 - 400)
        val w1 = windowRoot(row)
        val w2 = windowRoot(view())
        whenever(w2.childCount).thenReturn(2)
        val bubble = mock<AccessibilityNodeInfo>()
        assertFalse(expand(handler(listOf(w1, w2), bubble)))
        row.neverClicked()
    }

    /** Bubble active, both platform windows complete: the one hit is clicked (the #788 keep-all shape). */
    @Test
    fun `with the bubble active a single hit across complete platform windows is clicked`() = runTest {
        val row = payRow(top = 1774 - 400)
        val w1 = windowRoot(row)
        val w2 = windowRoot(view())
        val bubble = mock<AccessibilityNodeInfo>()
        assertTrue(expand(handler(listOf(w1, w2), bubble)))
        row.clicks(1)
    }

    /** L5: a BACKGROUND hit that would fail its refresh is outside the deciding set — the complete active hit is clicked. */
    @Test
    fun `an unprovable background hit does not veto the active target`() = runTest {
        val row = payRow(top = 1774 - 400)
        val active = windowRoot(row)
        val bgRow = payRow(top = 1774 - 200)
        whenever(bgRow.refresh()).thenReturn(false)
        val background = windowRoot(bgRow)
        assertTrue(expand(handler(listOf(active, background), active)))
        row.clicks(1)
        bgRow.neverClicked()
    }

    /**
     * A small same-package dialog is active (complete, no hit) over the still-sliding receipt sheet in a
     * background window: the sheet's exact hit is used (#788 "active contributes none → keep all"), NOT
     * a frozen-bounds strategy-3 guess.
     */
    @Test
    fun `an active dialog without a hit defers to the background sheet's exact hit`() = runTest {
        val dialog = windowRoot(view(clickable = true, children = listOf(view(cls = "android.widget.TextView", text = "Got it"))))
        val row = payRow(top = 1774 - 400)
        val sheet = windowRoot(row)
        assertTrue(expand(handler(listOf(dialog, sheet), dialog)))
        row.clicks(1)
    }

    /** N2: when the active window has the hit, the background windows are never walked. */
    @Test
    fun `background windows are not walked when the active window has the hit`() = runTest {
        val row = payRow(top = 1774 - 400)
        val active = windowRoot(row)
        val background = windowRoot(view())
        assertTrue(expand(handler(listOf(active, background), active)))
        verify(background, never()).getChild(any())
    }

    /** P3: under the bubble the deciding set is every window, so an UNREADABLE window beside a lone complete hit → abort. */
    @Test
    fun `an unreadable window under the bubble makes a lone hit inconclusive`() = runTest {
        val row = payRow(top = 1774 - 400)
        val w1 = windowRoot(row)
        val bubble = mock<AccessibilityNodeInfo>()
        assertFalse(expand(handler(listOf(w1), bubble, unreadableWindows = 1)))
        row.neverClicked()
    }

    /** P3: an unreadable window does not matter when the active platform window has the hit. */
    @Test
    fun `an unreadable window does not matter when the active platform window has the hit`() = runTest {
        val row = payRow(top = 1774 - 400)
        val active = windowRoot(row)
        assertTrue(expand(handler(listOf(active), active, unreadableWindows = 1)))
        row.clicks(1)
    }

    /** R6: the #788 shape with the ACTIVE sheet's title stale — the background popup's twin must NOT get the tap. */
    @Test
    fun `a stale active-window title does not hand the tap to the background twin`() = runTest {
        val sheetTitle = view(cls = "android.widget.TextView", text = "Decline offer", refreshes = false)
        val sheetButton = view(clickable = true, children = listOf(sheetTitle))
        val active = windowRoot(sheetButton, byId = listOf(sheetTitle))
        val popupTitle = view(cls = "android.widget.TextView", text = "Decline")
        val popupButton = view(clickable = true, children = listOf(popupTitle))
        val background = windowRoot(popupButton, byId = listOf(popupTitle))

        assertFalse(confirmDecline(handler(listOf(active, background), active)))
        sheetButton.neverClicked(); popupButton.neverClicked()
    }

    /** S1: the #788 stale-title shape where the sheet vanished mid-walk (parent = null, refresh fails) → abort, not the twin. */
    @Test
    fun `a vanished active-window title with no reachable owner does not hand the tap to the twin`() = runTest {
        val sheetTitle = view(cls = "android.widget.TextView", text = "Decline offer", refreshes = false)
        val sheetButton = view(clickable = true, children = listOf(sheetTitle))
        whenever(sheetTitle.parent).thenReturn(null) // the parent chain is gone
        val active = windowRoot(sheetButton, byId = listOf(sheetTitle))
        val popupTitle = view(cls = "android.widget.TextView", text = "Decline")
        val popupButton = view(clickable = true, children = listOf(popupTitle))
        val background = windowRoot(popupButton, byId = listOf(popupTitle))

        assertFalse(confirmDecline(handler(listOf(active, background), active)))
        sheetButton.neverClicked(); popupButton.neverClicked()
    }

    /** S2: the ACTIVE window's bounds walk is cut → a background control at the rect must NOT become the sole survivor. */
    @Test
    fun `a cut active bounds walk never falls back to a background window`() = runTest {
        val legacy = expandRef.copy(labelHintHashes = emptyList(), labelHintsComplete = false)
        val active = windowRoot()
        whenever(active.childCount).thenReturn(Int.MAX_VALUE)
        val bgRow = payRow() // exactly at the captured rect
        val background = windowRoot(bgRow)
        assertFalse(expand(handler(listOf(active, background), active), legacy))
        bgRow.neverClicked()
    }

    /**
     * T1: bubble active (no platform window active), window A's bounds walk is CUT (the intended control
     * past the cut), window B holds a twin at the captured rect → abort, B is NOT clicked.
     */
    @Test
    fun `under the bubble any cut bounds walk aborts`() = runTest {
        val legacy = expandRef.copy(labelHintHashes = emptyList(), labelHintsComplete = false)
        val a = windowRoot()
        whenever(a.childCount).thenReturn(Int.MAX_VALUE)
        val twin = payRow()
        val b = windowRoot(twin)
        val bubble = mock<AccessibilityNodeInfo>()
        assertFalse(expand(handler(listOf(a, b), bubble), legacy))
        twin.neverClicked()
    }

    /** T1 control: with a verified ACTIVE-window candidate, a cut background window does not matter. */
    @Test
    fun `a cut background bounds walk does not matter when the active window has the target`() = runTest {
        val legacy = expandRef.copy(labelHintHashes = emptyList(), labelHintsComplete = false)
        val row = payRow()
        val active = windowRoot(row)
        val bg = windowRoot()
        whenever(bg.childCount).thenReturn(Int.MAX_VALUE)
        assertTrue(expand(handler(listOf(active, bg), active), legacy))
        row.clicks(1)
    }

    /** T3: bubble active, one platform window unreadable, a twin at the rect in the readable one → no click. */
    @Test
    fun `under the bubble an unreadable window counts as a cut bounds walk`() = runTest {
        val legacy = expandRef.copy(labelHintHashes = emptyList(), labelHintsComplete = false)
        val twin = payRow()
        val readable = windowRoot(twin)
        val bubble = mock<AccessibilityNodeInfo>()
        assertFalse(expand(handler(listOf(readable), bubble, unreadableWindows = 1), legacy))
        twin.neverClicked()
    }
}
