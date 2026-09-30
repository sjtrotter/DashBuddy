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
 * #1149 — strategy 2b, the label re-find: labels before geometry, exact fingerprints, nesting, the bounded one-pass walk, incompleteness and the region veto, twins, and the bounded strategy-3 fallback. Driven through [UiInteractionHandler.performVerifiedClick] over mocked live trees
 * ([UiInteractionHandlerTapTestKit]); every refusal path has a test that goes red if its guard is removed.
 */
@RunWith(RobolectricTestRunner::class)
class UiInteractionHandlerSemanticRefindTest : UiInteractionHandlerTapTestKit() {

    /** The #1102 shape: the sheet slid 400 px after the bind was captured. The bounds walk cannot reach it; labels can. */
    @Test
    fun `a slid id-less row is re-found by its labels and clicked`() = runTest {
        val row = payRow(top = 1774 - 400)
        assertTrue(expand(handler(windowRoot(row))))
        row.clicks(1)
    }

    /** Labels before geometry: a stranger sitting at the exact captured rect loses to the slid row. */
    @Test
    fun `the labelled row wins over a stranger at the captured rect`() = runTest {
        val stranger = view(clickable = true, bounds = rowRect, children = listOf(
            view(cls = "android.widget.TextView", bounds = rowRect, text = "Continue dashing"),
        ))
        val row = payRow(top = 1774 - 400)
        assertTrue(expand(handler(windowRoot(stranger, row))))
        row.clicks(1)
        stranger.neverClicked()
    }

    /** A Compose row advertises ACTION_CLICK without setting isClickable — 2b takes it (D1's definition). */
    @Test
    fun `a row that only advertises ACTION_CLICK is found by labels and clicked`() = runTest {
        val row = payRow(top = 1774 - 400, clickable = false, advertisesClick = true)
        assertTrue(expand(handler(windowRoot(row))))
        row.clicks(1)
    }

    /**
     * Review I3: a clickable wrapper around the slid row does NOT inherit the row's labels (they are
     * the row's), so it is no candidate and the row itself is clicked.
     */
    @Test
    fun `a label-less clickable wrapper does not shadow the slid row inside it`() = runTest {
        val inner = payRow(top = 1774 - 400)
        val wrapper = view(clickable = true, bounds = Rect(20, 1360, 1060, 1520), children = listOf(inner))
        assertTrue(expand(handler(windowRoot(wrapper))))
        inner.clicks(1)
        wrapper.neverClicked()
    }

    /** A wrapper that carries its OWN copy of the fingerprint around the row is undecidable — abort (#1093 rule). */
    @Test
    fun `a wrapper with its own copy of the labels around the row aborts`() = runTest {
        val inner = payRow(top = 1774 - 400)
        val wrapper = view(clickable = true, bounds = Rect(20, 1360, 1060, 1520), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Expand"), inner,
        ))
        assertFalse(expand(handler(windowRoot(wrapper))))
        wrapper.neverClicked(); inner.neverClicked()
    }

    /** ONE of the two hints is not identity: a slid control carrying only 'This offer' is not found. */
    @Test
    fun `a slid control carrying only one hint is not clicked`() = runTest {
        val half = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"),
        ))
        assertFalse(expand(handler(windowRoot(half))))
        half.neverClicked()
    }

    /**
     * Bounded ingestion: a row deeper than MAX_TREE_DEPTH is outside the 2b walk — and a cut walk
     * ABORTS the resolution (a partial scan can leave one wrong survivor; #1102 review constraint 2).
     */
    @Test
    fun `the semantic walk is depth bounded`() = runTest {
        val row = payRow(top = 1774 - 400)
        var top: AccessibilityNodeInfo = row
        repeat(TreeLimits.MAX_TREE_DEPTH) { top = view(children = listOf(top)) }
        // row now sits at depth MAX_TREE_DEPTH + 1 below the window root
        assertFalse(expand(handler(windowRoot(top))))
        row.neverClicked()

        // Control: the same row two levels shallower — its own leaf labels at depth MAX_TREE_DEPTH
        // — is inside the bound and found.
        val row2 = payRow(top = 1774 - 400)
        var top2: AccessibilityNodeInfo = row2
        repeat(TreeLimits.MAX_TREE_DEPTH - 2) { top2 = view(children = listOf(top2)) }
        assertTrue(expand(handler(windowRoot(top2))))
        row2.clicks(1)
    }

    /** Bounded ingestion: a row past MAX_TREE_NODES fetches is outside the 2b walk, and the cut aborts. */
    @Test
    fun `the semantic walk is node-count bounded`() = runTest {
        val row = payRow(top = 1774 - 400)
        val fillers = List(TreeLimits.MAX_TREE_NODES) { filler() }
        assertFalse(expand(handler(windowRoot(*(fillers + row).toTypedArray()))))
        row.neverClicked()
    }

    /** A pre-#1093 ref (no hints) skips 2b: a slid row is NOT re-found, exactly as before. */
    @Test
    fun `a hint-less ref skips the semantic walk and keeps the exact-only bounds behaviour`() = runTest {
        val legacy = expandRef.copy(labelHintHashes = emptyList())
        val slid = payRow(top = 1774 + 40)
        assertFalse(expand(handler(windowRoot(slid)), legacy))
        slid.neverClicked()

        val exact = payRow()
        assertTrue(expand(handler(windowRoot(exact)), legacy))
        exact.clicks(1)
    }

    /**
     * #1102 review constraint 2: a budget cut can leave exactly one WRONG survivor. Here a clickable
     * row-shaped stranger sits early in the tree and the real row lies past the fetch budget; the cut
     * must abort, not hand the tap to the lone survivor.
     */
    @Test
    fun `a budget-cut walk with one early survivor aborts rather than clicking it`() = runTest {
        val early = payRow(top = 1774 - 400)
        val fillers = List(TreeLimits.MAX_TREE_NODES) { filler() }
        val real = payRow(top = 1774 - 380)
        assertFalse(expand(handler(windowRoot(*(listOf(early) + fillers + real).toTypedArray()))))
        early.neverClicked(); real.neverClicked()
    }

    /**
     * #1102 review constraint 3: null children spend budget — the 2b walk over a root reporting 100 000
     * null children stops at the bound. (The ref's rect is degenerate so the UNBUDGETED strategy-3
     * fallback — L1's fall-through, #1102's pre-existing residual — does not run and muddy the count.)
     */
    @Test
    fun `null children spend the fetch budget and the walk stops at the bound`() = runTest {
        val root = windowRoot()
        whenever(root.childCount).thenReturn(100_000)
        assertFalse(expand(handler(root), expandRef.copy(boundsInScreen = BoundingBox(0, 0, 0, 0))))
        verify(root, org.mockito.kotlin.atMost(TreeLimits.MAX_TREE_NODES)).getChild(any())
    }

    /**
     * #1102 review constraint 1: containment is not identity. A clickable parent card holding a
     * NON-clickable copy of the row plus other text contains every hint — with no geometric evidence
     * it must not be tapped (the exact fingerprint rejects the superset).
     */
    @Test
    fun `a clickable parent card that merely contains the row's labels is not clicked`() = runTest {
        val innerRow = payRow(top = 1374, clickable = false)
        val card = view(clickable = true, bounds = Rect(0, 1300, 1080, 1700), children = listOf(
            innerRow, view(cls = "android.widget.TextView", text = "Continue dashing"),
        ))
        assertFalse(expand(handler(windowRoot(card))))
        card.neverClicked(); innerRow.neverClicked()
    }

    /**
     * #1102 review constraint 4: label collection never reads another package's embedded subtree, so
     * a same-package container cannot acquire the hints from foreign content.
     */
    @Test
    fun `a container whose labels come from a foreign-package subtree is not clicked`() = runTest {
        val foreign = view(packageName = "com.example.other", children = listOf(
            view(cls = "android.widget.TextView", text = "This offer", packageName = "com.example.other"),
            view(desc = "Expand", packageName = "com.example.other"),
        ))
        val container = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(foreign))
        assertFalse(expand(handler(windowRoot(container))))
        container.neverClicked()

        // Control: the same shape with same-package content IS the row.
        val local = view(children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Expand"),
        ))
        val container2 = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(local))
        assertTrue(expand(handler(windowRoot(container2))))
        container2.clicks(1)
    }

    /** I4a/b: one unreadable child beside a matching survivor — the survivor is unproven unique, no click. */
    @Test
    fun `a null child beside a matching survivor aborts`() = runTest {
        val row = payRow(top = 1774 - 400)
        val root = windowRoot(row)
        whenever(root.childCount).thenReturn(2) // slot 1 advertises a child that reads null
        assertFalse(expand(handler(root)))
        row.neverClicked()
    }

    /** I4a/b: a row whose own scan is cut (> LABEL_SCAN_NODES fetches) next to a shallow twin — no click. */
    @Test
    fun `a real row with an over-budget scan next to a shallow twin aborts`() = runTest {
        val bigRow = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Expand"),
        ) + List(NodeRef.LABEL_SCAN_NODES) { view() })
        val twin = payRow(top = 1774 - 200)
        assertFalse(expand(handler(windowRoot(bigRow, twin))))
        bigRow.neverClicked(); twin.neverClicked()
    }

    /**
     * Vet decision on I2 × I4b: the depth cut is the HORIZON. The depth-4 title is outside the
     * fingerprint on both sides, so {this offer, expand} fully determines it — 2b finds and clicks it.
     */
    @Test
    fun `a row whose title sits past the label depth is found by its in-horizon fingerprint and clicked`() = runTest {
        val row = deepTitledRow(nullChild = false)
        assertTrue(expand(handler(windowRoot(row))))
        row.clicks(1)
    }

    /** ...but an unreadable child INSIDE the horizon leaves in-horizon labels unseen — fail closed. */
    @Test
    fun `the same row with a null child inside the horizon is not clicked`() = runTest {
        val row = deepTitledRow(nullChild = true)
        assertFalse(expand(handler(windowRoot(row))))
        row.neverClicked()
    }

    /**
     * Review I7: discovery is one pass — the row's children are fetched ONCE by the 2b walk (its label
     * region is derived from them) and once more only by the owner's own verification scan. The old
     * scan-then-descend walk fetched them twice during discovery (3 in total).
     */
    @Test
    fun `the semantic walk fetches each child once`() = runTest {
        val row = payRow(top = 1774 - 400)
        assertTrue(expand(handler(windowRoot(row))))
        verify(row, times(2)).getChild(eq(0))
        verify(row, times(2)).getChild(eq(1))
    }

    /** Two rows with identical fingerprints, one sitting exactly on the captured rect — the stale rect must not decide. */
    @Test
    fun `semantic twins abort even when one occupies the captured rect`() = runTest {
        val onRect = payRow()
        val elsewhere = payRow(top = 1774 - 400)
        assertFalse(expand(handler(windowRoot(onRect, elsewhere))))
        onRect.neverClicked(); elsewhere.neverClicked()
    }

    /** A big unrelated clickable card (over the slot cap, carrying a foreign label) beside the exact row does not veto. */
    @Test
    fun `a big unrelated clickable card beside the exact row does not veto — the row is clicked`() = runTest {
        val card = view(clickable = true, bounds = Rect(0, 200, 1080, 1200), children = listOf(
            view(cls = "android.widget.TextView", text = "Order details"),
        ) + List(NodeRef.LABEL_SCAN_NODES) { view() })
        val row = payRow(top = 1774 - 400)
        assertTrue(expand(handler(windowRoot(card, row))))
        row.clicks(1)
        card.neverClicked()
    }

    /** A partially-seen region whose visible labels are a subset of the ref's could BE the control — abort. */
    @Test
    fun `a partially seen region whose visible labels fit the fingerprint aborts`() = runTest {
        val partial = view(clickable = true, bounds = Rect(0, 200, 1080, 1200), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"),
        ) + List(NodeRef.LABEL_SCAN_NODES) { view() })
        val row = payRow(top = 1774 - 400)
        assertFalse(expand(handler(windowRoot(partial, row))))
        row.neverClicked(); partial.neverClicked()
    }

    /** Two complete twins; the refresh introduces a null child on one — its twin must NOT become the sole survivor. */
    @Test
    fun `a twin that becomes unprovable after refresh aborts the tap`() = runTest {
        val a = payRow(top = 1774 - 400)
        val b = payRow(top = 1774 - 200)
        var aChildren = 2
        whenever(a.childCount).thenAnswer { aChildren }
        whenever(a.refresh()).thenAnswer { aChildren = 3; true } // slot 2 now reads null
        assertFalse(expand(handler(windowRoot(a, b))))
        a.neverClicked(); b.neverClicked()
    }

    /** A hint-less ref and an action-only (Compose) control at the exact rect: found by the bounds walk and clicked. */
    @Test
    fun `the bounds walk finds an action-only control at the exact rect`() = runTest {
        val legacy = expandRef.copy(labelHintHashes = emptyList(), labelHintsComplete = false)
        val row = payRow(clickable = false, advertisesClick = true)
        assertTrue(expand(handler(windowRoot(row)), legacy))
        row.clicks(1)
    }

    /** P1: a hostile childCount (Int.MAX_VALUE) is never an allocation — bounded, incomplete, no click. */
    @Test
    fun `a hostile child count is bounded, not allocated`() = runTest {
        val root = windowRoot()
        whenever(root.childCount).thenReturn(Int.MAX_VALUE)
        assertFalse(expand(handler(root), expandRef.copy(boundsInScreen = BoundingBox(0, 0, 0, 0))))
        verify(root, org.mockito.kotlin.atMost(TreeLimits.MAX_TREE_NODES)).getChild(any())
    }

    /** R8: twins abort unconditionally — even when a stored text appears in exactly one of them. */
    @Test
    fun `semantic twins abort even when the stored text is in one of them`() = runTest {
        fun row(top: Int, amount: String) = view(clickable = true, bounds = Rect(36, top, 1044, top + 126), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Expand"),
            view(cls = "android.widget.TextView", text = amount),
        ))
        val a = row(1374, "\$9.00")
        val b = row(1574, "\$12.50")
        assertFalse(expand(handler(windowRoot(a, b)), expandRef.copy(text = "\$12.50")))
        a.neverClicked(); b.neverClicked()
    }

    /** R4: a label-less (icon-only) clickable region cut at the slot cap is no evidence — the exact row still clicks. */
    @Test
    fun `a label-less over-cap region does not veto the exact row`() = runTest {
        val iconPanel = view(clickable = true, bounds = Rect(0, 200, 1080, 1200), children = List(NodeRef.LABEL_SCAN_NODES + 1) { view() })
        val row = payRow(top = 1774 - 400)
        assertTrue(expand(handler(windowRoot(iconPanel, row))))
        row.clicks(1)
    }

    /** R5: strategy 3 is bounded too — a hostile childCount under a hint-less ref stops at the budget, no click. */
    @Test
    fun `the bounds walk is bounded by the tree budget`() = runTest {
        val root = windowRoot()
        whenever(root.childCount).thenReturn(Int.MAX_VALUE)
        val legacy = expandRef.copy(labelHintHashes = emptyList(), labelHintsComplete = false)
        assertFalse(expand(handler(root), legacy))
        verify(root, org.mockito.kotlin.atMost(TreeLimits.MAX_TREE_NODES)).getChild(any())
    }
}
