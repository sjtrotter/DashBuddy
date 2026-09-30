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
 * #1149 — bind-time vs fire-time parity: one clickability predicate, the owner-region fingerprint, completeness and truncation on the ref, foreign/unreadable/budget-cut children, the shared text cap and the whole-label hash key. Driven through [UiInteractionHandler.performVerifiedClick] over mocked live trees
 * ([UiInteractionHandlerTapTestKit]); every refusal path has a test that goes red if its guard is removed.
 */
@RunWith(RobolectricTestRunner::class)
class UiInteractionHandlerBindFireParityTest : UiInteractionHandlerTapTestKit() {

    /**
     * Bind and fire agree the action-only chevron owns "Details", so the fingerprint is {this offer,
     * expand}: the REAL row is found, never the competitor whose non-clickable "Details" makes it a
     * superset. (With bind reading isClickable only, bind absorbed "Details" and the competitor
     * became the sole exact survivor — an abort turned into a wrong click.)
     */
    @Test
    fun `an action-only descendant is owned identically at bind and fire — the real row is found`() = runTest {
        val row = rowWithActionOnlyChevron(top = 1374)
        val competitor = view(clickable = true, bounds = Rect(36, 1600, 1044, 1726), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Expand"), view(desc = "Details"),
        ))
        val ref = bindRef(row)
        assertTrue(expand(handler(windowRoot(row, competitor)), ref))
        row.clicks(1)
        competitor.neverClicked()
    }

    @Test
    fun `two genuine twins with action-only chevrons still abort`() = runTest {
        val a = rowWithActionOnlyChevron(top = 1374)
        val b = rowWithActionOnlyChevron(top = 1600)
        assertFalse(expand(handler(windowRoot(a, b)), bindRef(a)))
        a.neverClicked(); b.neverClicked()
    }

    /** A bind scan cut at the slot cap cannot claim an exact fingerprint: 2b is skipped, the slid row is not re-found. */
    @Test
    fun `a ref whose bind scan was cut at the slot cap skips 2b — strategy 3 still runs`() = runTest {
        val bound = view(clickable = true, bounds = rowRect, children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Expand"),
        ) + List(NodeRef.LABEL_SCAN_NODES) { view() })
        val ref = bindRef(bound)
        assertFalse(ref.labelHintsComplete)
        assertFalse(ref.hasExactFingerprint)

        val slid = payRow(top = 1774 - 400)
        assertFalse("no 2b, and the bounds walk cannot reach a row 400 px away", expand(handler(windowRoot(slid)), ref))
        slid.neverClicked()

        val atRect = payRow()
        assertTrue("strategy 3 (containment) still finds the row at its rect", expand(handler(windowRoot(atRect)), ref))
        atRect.clicks(1)
    }

    /**
     * P7: an owner with exactly MAX_LABEL_HINTS labels is PROVABLE (found by 2b after a slide); one with
     * MAX_LABEL_HINTS + 1 was truncated at bind — no 2b, strategy 3 still runs at the captured rect.
     */
    @Test
    fun `exactly MAX_LABEL_HINTS labels is provable and one more is truncated and skips 2b`() = runTest {
        val words = listOf("This offer", "Expand", "Base pay", "Tip", "Peak pay", "Details", "Adjustments")
        fun rowOf(n: Int, top: Int) = view(clickable = true, bounds = Rect(36, top, 1044, top + 126),
            children = words.take(n).map { view(cls = "android.widget.TextView", text = it) })

        val six = rowOf(NodeRef.MAX_LABEL_HINTS, 1774 - 400)
        val sixRef = bindRef(six)
        assertTrue(sixRef.hasExactFingerprint)
        assertTrue("found by 2b after a 400 px slide", expand(handler(windowRoot(six)), sixRef))
        six.clicks(1)

        val sevenRef = bindRef(rowOf(NodeRef.MAX_LABEL_HINTS + 1, 1774))
        assertFalse(sevenRef.hasExactFingerprint)
        val slid = rowOf(NodeRef.MAX_LABEL_HINTS + 1, 1774 - 400)
        assertFalse(expand(handler(windowRoot(slid)), sevenRef))
        slid.neverClicked()
        val atRect = rowOf(NodeRef.MAX_LABEL_HINTS + 1, 1774)
        assertTrue("strategy 3 (containment) still finds it at its rect", expand(handler(windowRoot(atRect)), sevenRef))
        atRect.clicks(1)
    }

    /** L2: a bind on the TITLE of a clickable row fingerprints the row (its owner), so 2b finds the row. */
    @Test
    fun `a bind on a row's title fingerprints the owner row — 2b finds the row`() = runTest {
        val row = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Details"),
        ))
        val root = windowRoot(row)
        val titleUi = root.toUiNode()!!.findNodes { it.text == "This offer" }.single()
        val ref = bindRefOf(titleUi).copy(classNameHint = "android.widget.TextView")
        assertTrue(ref.hasExactFingerprint)
        assertEquals("android.view.View", ref.ownerClassHint)
        assertTrue(expand(handler(root), ref))
        row.clicks(1)
    }

    /** L3: an embedded foreign-package subtree is outside the fingerprint on BOTH sides. */
    @Test
    fun `an embedded foreign subtree is excluded at bind and at fire`() = runTest {
        val other = "com.example.other"
        val row = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"),
            view(packageName = other, children = listOf(view(cls = "android.widget.TextView", text = "Sponsored", packageName = other))),
        ))
        val root = windowRoot(row)
        val ref = bindRefOf(root.toUiNode()!!.findNodes { it.text == "This offer" }.single().parent!!)
        assertEquals(listOfNotNull(NodeRef.hintHash("This offer")), ref.labelHintHashes)
        assertTrue(expand(handler(root), ref))
        row.clicks(1)
    }

    /** L4: a row with a child the mapper could not read is never certified complete — no exact fingerprint. */
    @Test
    fun `a bind over an unreadable child is incomplete`() = runTest {
        val row = payRow(top = 1374)
        whenever(row.childCount).thenReturn(3) // slot 2 reads null at mapping time
        val ref = bindRef(row)
        assertFalse(ref.labelHintsComplete)
        assertFalse(ref.hasExactFingerprint)
    }

    /** N7: an owner with NO className binds ownerClassHint = null, which means "no class filter" — 2b still finds it. */
    @Test
    fun `an owner without a class name is still found by 2b`() = runTest {
        val row = payRow(top = 1774 - 400)
        whenever(row.className).thenReturn(null)
        val ref = bindRef(row).copy(classNameHint = "android.view.View")
        assertEquals(null, ref.ownerClassHint)
        assertTrue(ref.hasExactFingerprint)
        assertTrue(expand(handler(windowRoot(row)), ref))
        row.clicks(1)
    }

    /** N5: a foreign id-less control carrying "This offer" never yields an exact fingerprint (a foreign node is never an owner). */
    @Test
    fun `a foreign control never yields an exact fingerprint`() = runTest {
        val foreign = view(clickable = true, desc = "This offer", packageName = "com.example.other")
        val root = windowRoot(foreign)
        val ref = bindRefOf(root.toUiNode()!!.findNodes { it.contentDescription == "This offer" }.single())
        assertFalse(ref.hasExactFingerprint)
        assertTrue(ref.labelHintHashes.isEmpty())

        // ...nor does a same-package label whose owner walk would cross into a foreign clickable.
        val inner = view(cls = "android.widget.TextView", text = "This offer")
        val foreignOwner = view(clickable = true, packageName = "com.example.other", children = listOf(inner))
        val root2 = windowRoot(foreignOwner)
        val ref2 = bindRefOf(root2.toUiNode()!!.findNodes { it.text == "This offer" }.single())
        assertFalse(ref2.hasExactFingerprint)
    }

    /** A depth cut below the row (inside its label horizon) makes the bind incomplete. */
    @Test
    fun `a mapper depth cut inside the horizon makes the bind incomplete`() = runTest {
        val row = view(clickable = true, desc = "Row", children = listOf(
            view(cls = "android.widget.TextView", text = "This offer", children = listOf(view(children = listOf(view(text = "deep"))))),
        ))
        // Put the row so its grandchild sits at the mapper's depth limit: the grandchild's child is refused.
        var top: AccessibilityNodeInfo = row
        repeat(TreeLimits.MAX_TREE_DEPTH - 3) { top = view(children = listOf(top)) }
        val root = windowRoot(top)
        val rowUi = root.toUiNode()!!.findNodes { it.contentDescription == "Row" }.single()
        assertFalse(bindRefOf(rowUi).labelHintsComplete)
    }

    /** Node-budget exhaustion that drops one of the row's own children makes the bind incomplete. */
    @Test
    fun `a mapper node-budget cut of the row's child makes the bind incomplete`() = runTest {
        // Pre-order admission: root + big + 3 996 fillers + row + its title = exactly MAX_TREE_NODES, so
        // the row's SECOND child is the first node the budget refuses (its first child is fully read —
        // without N6 the row would look complete: one slot, well under the label cap).
        val big = view(children = List(TreeLimits.MAX_TREE_NODES - 4) { filler() })
        val row = view(clickable = true, desc = "Row", children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Details"),
        ))
        val root = windowRoot(big, row)
        val rowUi = root.toUiNode()!!.findNodes { it.contentDescription == "Row" }.single()
        assertFalse(bindRefOf(rowUi).labelHintsComplete)
    }

    /** R2: one text cap on both sides — a 4 096-space description + "Primary" is blank at bind AND live. */
    @Test
    fun `the text cap is applied identically at bind and at fire`() = runTest {
        val padded = " ".repeat(cloud.trotter.dashbuddy.domain.pipeline.UiTextBounds.MAX_TEXT_LENGTH) + "Primary"
        val row = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = padded),
        ))
        val ref = bindRef(row)
        assertEquals(listOfNotNull(NodeRef.hintHash("This offer")), ref.labelHintHashes)
        assertTrue(expand(handler(windowRoot(row)), ref))
        row.clicks(1)
    }

    /** T2: the fingerprint hashes the WHOLE label — a Bob row is not a 2b hit for Alice's long-titled bind. */
    @Test
    fun `a long label differing only past 40 chars is not a 2b hit`() = runTest {
        fun row(who: String, top: Int) = view(clickable = true, bounds = Rect(36, top, 1044, top + 126), children = listOf(
            view(cls = "android.widget.TextView", text = "Decline this delivery offer from merchant $who"),
        ))
        val ref = bindRef(row("Alice", 1774))
        val bob = row("Bob", 1774 - 400)
        assertFalse(expand(handler(windowRoot(bob)), ref))
        bob.neverClicked()
    }
}
