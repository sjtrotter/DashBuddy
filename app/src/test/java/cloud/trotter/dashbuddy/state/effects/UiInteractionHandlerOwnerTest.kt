package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.TreeLimits
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toUiNode
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
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
 * #1149 — owner-first resolution, refresh before dispatch, and label re-resolution (strategy 2b)
 * BEFORE geometry, driven through [UiInteractionHandler.performVerifiedClick] over mocked live
 * trees (the [UiInteractionHandlerTieTest] harness style). Every refusal path here has a test that
 * goes red if its guard is removed.
 */
@RunWith(RobolectricTestRunner::class)
class UiInteractionHandlerOwnerTest {

    private val pkg = "com.doordash.driverapp"
    private val titleId = "com.doordash.driverapp:id/textView_prism_button_title"

    /** A mocked live node whose children's `parent` points back at it (the owner walk climbs it). */
    @Suppress("DEPRECATION") // getActions(): the legacy bitmask the P6 predicate reads
    private fun view(
        cls: String = "android.view.View", clickable: Boolean = false, advertisesClick: Boolean = false,
        bounds: Rect = Rect(0, 0, 10, 10), text: String? = null, desc: String? = null,
        children: List<AccessibilityNodeInfo> = emptyList(), refreshes: Boolean = true,
        packageName: String = pkg,
    ): AccessibilityNodeInfo {
        val node = mock<AccessibilityNodeInfo>()
        whenever(node.packageName).thenReturn(packageName)
        whenever(node.className).thenReturn(cls)
        whenever(node.text).thenReturn(text)
        whenever(node.contentDescription).thenReturn(desc)
        whenever(node.isClickable).thenReturn(clickable)
        whenever(node.actions).thenReturn(if (advertisesClick) AccessibilityNodeInfo.ACTION_CLICK else 0) // P6: the bitmask
        whenever(node.actionList).thenReturn(
            if (advertisesClick) listOf(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) else emptyList(),
        )
        whenever(node.childCount).thenReturn(children.size)
        whenever(node.getChild(any())).thenAnswer { children.getOrNull(it.getArgument(0)) }
        for (c in children) whenever(c.parent).thenReturn(node)
        whenever(node.getBoundsInScreen(any())).thenAnswer { (it.arguments[0] as Rect).set(bounds) }
        whenever(node.performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))).thenReturn(true)
        whenever(node.refresh()).thenReturn(refreshes)
        return node
    }

    /** A cheap leaf for budget tests (thousands of them): only what the walk reads. */
    private fun filler(): AccessibilityNodeInfo = mock<AccessibilityNodeInfo>().also { whenever(it.packageName).thenReturn(pkg) }

    private fun windowRoot(vararg children: AccessibilityNodeInfo, byId: List<AccessibilityNodeInfo> = emptyList()): AccessibilityNodeInfo {
        val root = view(cls = "android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400), children = children.toList())
        whenever(root.packageName).thenReturn(pkg)
        whenever(root.findAccessibilityNodeInfosByViewId(eq(titleId))).thenReturn(byId)
        return root
    }

    private fun handler(root: AccessibilityNodeInfo): UiInteractionHandler {
        val source = mock<AccessibilitySource> {
            on { getLiveWindowRoots() } doReturn AccessibilitySource.LiveRoots(root, listOf(root))
        }
        return UiInteractionHandler(source)
    }

    private fun AccessibilityNodeInfo.clicks(n: Int) = verify(this, times(n)).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    private fun AccessibilityNodeInfo.neverClicked() = verify(this, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))

    // ---------------------------------------------------------------- owner resolution (D1)

    private val idRef = NodeRef(
        viewIdSuffix = titleId, text = null, classNameHint = null,
        boundsInScreen = BoundingBox(0, 0, 10, 10), pathFingerprint = "",
    )

    private suspend fun confirmDecline(h: UiInteractionHandler, ref: NodeRef = idRef) = h.performVerifiedClick(
        ref = ref, expectedPackage = pkg,
        expectation = RuleAction.CONFIRM_DECLINE.verification, description = "confirm decline",
    )

    /**
     * Two candidates — the button's title TextView and the button itself — are ONE control. Before
     * #1149 they were two verified candidates with no decisive ranker tier (no stored text, no
     * overlap) → a #734 abort; deduped by owner they are a single verified target and clicked.
     */
    @Test
    fun `a title and its button resolve to one owner and are clicked — no tie abort`() = runTest {
        val title = view(cls = "android.widget.TextView", bounds = Rect(60, 2020, 900, 2100), text = "Decline offer")
        val button = view(clickable = true, bounds = Rect(40, 2000, 1000, 2120), children = listOf(title))
        val root = windowRoot(button, byId = listOf(title, button))

        assertTrue(confirmDecline(handler(root)))
        button.clicks(1)
        title.neverClicked()
    }

    /**
     * Review I3 (replaces the compound-owner rule): a footer holding clickable Accept AND Decline
     * cannot borrow its buttons' labels. Bound on the footer itself, its OWN labels are empty, so the
     * Decline expectation fails and nothing is clicked.
     */
    @Test
    fun `a container never borrows a nested button's label — the expectation fails on it`() = runTest {
        val accept = view(clickable = true, children = listOf(view(cls = "android.widget.TextView", text = "Accept")))
        val decline = view(clickable = true, children = listOf(view(cls = "android.widget.TextView", text = "Decline")))
        val footer = view(clickable = true, bounds = Rect(0, 2000, 1080, 2200), children = listOf(accept, decline))
        val root = windowRoot(footer, byId = listOf(footer))

        assertFalse(confirmDecline(handler(root)))
        footer.neverClicked(); accept.neverClicked(); decline.neverClicked()
    }

    /**
     * Review I3: a legitimate composite row — its own "This offer" plus a clickable info chevron and a
     * clickable "Breakdown" link — is NOT refused (the old compound rule banned it). Its fingerprint is
     * {"this offer"} on both sides; the nested controls' labels are theirs.
     */
    @Test
    fun `a composite row with its own nested controls is found by its own labels and clicked`() = runTest {
        val chevron = view(clickable = true, desc = "Details")
        val link = view(clickable = true, children = listOf(view(cls = "android.widget.TextView", text = "Breakdown")))
        val row = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), chevron, link,
        ))
        val ref = expandRef.copy(labelHintHashes = listOfNotNull(NodeRef.hintHash("This offer")))
        assertTrue(expand(handler(windowRoot(row)), ref))
        row.clicks(1)
        chevron.neverClicked(); link.neverClicked()
    }

    /** Bound on the Decline title inside that same footer, the owner is the Decline button — clicked. */
    @Test
    fun `a title inside a footer resolves to its own button and is clicked`() = runTest {
        val acceptTitle = view(cls = "android.widget.TextView", text = "Accept")
        val declineTitle = view(cls = "android.widget.TextView", text = "Decline")
        val accept = view(clickable = true, children = listOf(acceptTitle))
        val decline = view(clickable = true, children = listOf(declineTitle))
        val footer = view(clickable = true, bounds = Rect(0, 2000, 1080, 2200), children = listOf(accept, decline))
        val root = windowRoot(footer, byId = listOf(declineTitle))

        assertTrue(confirmDecline(handler(root)))
        decline.clicks(1)
        footer.neverClicked(); accept.neverClicked()
    }

    @Test
    fun `a candidate with no clickable owner is dropped and nothing is clicked`() = runTest {
        val orphan = view(cls = "android.widget.TextView", text = "Decline offer")
        val shell = view(children = listOf(orphan)) // not clickable, and the window root is not either
        val root = windowRoot(shell, byId = listOf(orphan))

        assertFalse(confirmDecline(handler(root)))
        orphan.neverClicked(); shell.neverClicked()
    }

    @Test
    fun `a stale owner (refresh fails) is not clicked`() = runTest {
        val title = view(cls = "android.widget.TextView", text = "Decline offer")
        val button = view(clickable = true, children = listOf(title), refreshes = false)
        val root = windowRoot(button, byId = listOf(title))

        assertFalse(confirmDecline(handler(root)))
        button.neverClicked()
    }

    /**
     * Review I1: the owner is refreshed BEFORE verification. A recycled row that rebinds on refresh
     * (Decline → Accept) must be verified as what it now is — and so NOT clicked by a decline tap.
     */
    @Test
    fun `an owner that rebinds on refresh is verified post-refresh and not clicked`() = runTest {
        var label = "Decline offer"
        val button = view(clickable = true)
        whenever(button.text).thenAnswer { label }
        whenever(button.refresh()).thenAnswer { label = "Accept"; true }
        val root = windowRoot(button, byId = listOf(button))

        assertFalse(confirmDecline(handler(root)))
        verify(button, times(1)).refresh()
        button.neverClicked()
    }

    /**
     * Review I8: the matched title sits 5 levels below its button — past the owner scan's depth —
     * yet the match itself is the evidence: its own label counts, and the tap verifies as before.
     */
    @Test
    fun `a matched title deeper than the owner scan still verifies by its own label`() = runTest {
        val title = view(cls = "android.widget.TextView", text = "Decline offer")
        var chain: AccessibilityNodeInfo = title
        repeat(4) { chain = view(children = listOf(chain)) }
        val button = view(clickable = true, children = listOf(chain))
        val root = windowRoot(button, byId = listOf(title))

        assertTrue(confirmDecline(handler(root)))
        button.clicks(1)
    }

    /** Review J1: a SEPARATE matched title that rebinds Decline → Accept on refresh is read as Accept — not clicked. */
    @Test
    fun `a matched title that rebinds on refresh is read fresh and not clicked`() = runTest {
        var label = "Decline offer"
        val title = view(cls = "android.widget.TextView")
        whenever(title.text).thenAnswer { label }
        whenever(title.refresh()).thenAnswer { label = "Accept"; true }
        val button = view(clickable = true, children = listOf(title))
        val root = windowRoot(button, byId = listOf(title))

        assertFalse(confirmDecline(handler(root)))
        verify(title, times(1)).refresh()
        button.neverClicked()
    }

    /** Review J1: a foreign-package matched node lends its label to nothing. */
    @Test
    fun `a foreign-package matched node lends no label to its owner`() = runTest {
        val title = view(cls = "android.widget.TextView", text = "Decline offer", packageName = "com.example.other")
        val button = view(clickable = true, children = listOf(title))
        val root = windowRoot(button, byId = listOf(title))

        assertFalse(confirmDecline(handler(root)))
        button.neverClicked()
    }

    // ---------------------------------------------------------------- strategy 2b (D2)

    private val rowRect = Rect(36, 1774, 1044, 1900)

    private fun payRow(top: Int = 1774, clickable: Boolean = true, advertisesClick: Boolean = false) = view(
        clickable = clickable, advertisesClick = advertisesClick, bounds = Rect(36, top, 1044, top + 126), children = listOf(
            view(cls = "android.widget.TextView", bounds = Rect(72, top + 40, 241, top + 87), text = "This offer"),
            view(bounds = Rect(250, top + 45, 286, top + 81), desc = "Expand"),
        ),
    )

    private val expandRef = NodeRef(
        viewIdSuffix = null, text = null, classNameHint = "android.view.View",
        boundsInScreen = BoundingBox(rowRect.left, rowRect.top, rowRect.right, rowRect.bottom), pathFingerprint = "",
        labelHintHashes = listOfNotNull(NodeRef.hintHash("This offer"), NodeRef.hintHash("Expand")),
        labelHintsComplete = true,
        ownerClassHint = "android.view.View", // the id-less row binds itself: bound node = owner (#1149 L2)
    )

    private suspend fun expand(h: UiInteractionHandler, ref: NodeRef = expandRef) = h.performVerifiedClick(
        ref = ref, expectedPackage = pkg,
        expectation = RuleAction.EXPAND_EARNINGS.verification, description = "expand earnings",
    )

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

    /** An owner outside the scoped package is never a tap target. */
    @Test
    fun `a candidate whose owner belongs to another package is dropped`() = runTest {
        val title = view(cls = "android.widget.TextView", text = "Decline offer")
        val foreignButton = view(clickable = true, children = listOf(title), packageName = "com.example.other")
        val root = windowRoot(foreignButton, byId = listOf(title))
        assertFalse(confirmDecline(handler(root)))
        foreignButton.neverClicked()
    }

    // ---------------------------------------------------------------- review I4: incompleteness fails closed

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

    /** A row whose own title sits at depth 4 — past the shared horizon on BOTH sides. */
    private fun deepTitledRow(nullChild: Boolean): AccessibilityNodeInfo {
        val deep = view(children = listOf(view(children = listOf(view(children = listOf(view(text = "Full breakdown")))))))
        val row = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Expand"), deep,
        ))
        if (nullChild) whenever(row.childCount).thenReturn(4) // slot 3 (depth 1) advertises a child that reads null
        return row
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

    // ---------------------------------------------------------------- review I6: per-window truncation

    private fun handler(roots: List<AccessibilityNodeInfo>, active: AccessibilityNodeInfo, unreadableWindows: Int = 0): UiInteractionHandler {
        val source = mock<AccessibilitySource> {
            on { getLiveWindowRoots() } doReturn AccessibilitySource.LiveRoots(active, roots, unreadableWindows)
        }
        return UiInteractionHandler(source)
    }

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

    // ---------------------------------------------------------------- review I5: semantic twins

    /** Two rows with identical fingerprints, one sitting exactly on the captured rect — the stale rect must not decide. */
    @Test
    fun `semantic twins abort even when one occupies the captured rect`() = runTest {
        val onRect = payRow()
        val elsewhere = payRow(top = 1774 - 400)
        assertFalse(expand(handler(windowRoot(onRect, elsewhere))))
        onRect.neverClicked(); elsewhere.neverClicked()
    }

    // ---------------------------------------------------------------- review J2: one clickability predicate

    /** A row whose info chevron ADVERTISES ACTION_CLICK without the flag (the Compose shape). */
    private fun rowWithActionOnlyChevron(top: Int) = view(clickable = true, bounds = Rect(36, top, 1044, top + 126), children = listOf(
        view(cls = "android.widget.TextView", text = "This offer"),
        view(desc = "Expand"),
        view(clickable = false, advertisesClick = true, desc = "Details"),
    ))

    /** Bind the ref the production way: native mapping → the :domain label horizon (what Ruleset.buildNodeRef hashes). */
    private fun bindRef(live: AccessibilityNodeInfo): NodeRef = bindRefOf(live.toUiNode()!!)

    /** The production bind: NodeRef.bindHintsOf over a mapped node (what Ruleset.buildNodeRef hashes). */
    private fun bindRefOf(bound: cloud.trotter.dashbuddy.domain.model.accessibility.UiNode): NodeRef {
        val h = NodeRef.bindHintsOf(bound)
        return expandRef.copy(labelHintHashes = h.labelHintHashes, labelHintsComplete = h.complete, ownerClassHint = h.ownerClassHint)
    }

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

    // ---------------------------------------------------------------- review J3: completeness rides the ref

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

    // ---------------------------------------------------------------- review J4: the veto only where it could BE the fingerprint

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

    // ---------------------------------------------------------------- review J6: post-refresh unprovability

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

    // ---------------------------------------------------------------- review J7: strategy 3's predicate

    /** A hint-less ref and an action-only (Compose) control at the exact rect: found by the bounds walk and clicked. */
    @Test
    fun `the bounds walk finds an action-only control at the exact rect`() = runTest {
        val legacy = expandRef.copy(labelHintHashes = emptyList(), labelHintsComplete = false)
        val row = payRow(clickable = false, advertisesClick = true)
        assertTrue(expand(handler(windowRoot(row)), legacy))
        row.clicks(1)
    }

    // ---------------------------------------------------------------- review L2–L4: bind-time parity

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

    // ---------------------------------------------------------------- review L1 / L5: decideSemanticOutcome

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

    /** L5: two twins, the intended one's refresh fails — the other must NOT become the sole survivor. */
    @Test
    fun `a twin whose refresh fails aborts the tap`() = runTest {
        val a = payRow(top = 1774 - 400)
        val b = payRow(top = 1774 - 200)
        whenever(a.refresh()).thenReturn(false)
        assertFalse(expand(handler(windowRoot(a, b))))
        a.neverClicked(); b.neverClicked()
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

    // ---------------------------------------------------------------- review N1/N2: the deciding set falls back to all windows

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

    // ---------------------------------------------------------------- review N6: mapper budget cuts are unreadable too

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

    /** P2: the live owner walk never crosses a foreign hop — title → foreign wrapper → same-package button: no owner. */
    @Test
    fun `the live owner walk never crosses a foreign wrapper`() = runTest {
        val title = view(cls = "android.widget.TextView", text = "Decline offer")
        val wrapper = view(packageName = "com.example.other", children = listOf(title))
        val button = view(clickable = true, children = listOf(wrapper))
        val root = windowRoot(button, byId = listOf(title))
        assertFalse(confirmDecline(handler(root)))
        button.neverClicked()
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

    /** P1: a hostile childCount (Int.MAX_VALUE) is never an allocation — bounded, incomplete, no click. */
    @Test
    fun `a hostile child count is bounded, not allocated`() = runTest {
        val root = windowRoot()
        whenever(root.childCount).thenReturn(Int.MAX_VALUE)
        assertFalse(expand(handler(root), expandRef.copy(boundsInScreen = BoundingBox(0, 0, 0, 0))))
        verify(root, org.mockito.kotlin.atMost(TreeLimits.MAX_TREE_NODES)).getChild(any())
    }

    /** P4: a foreign child's text and description are never read — not by the walk, not by verification. */
    @Test
    fun `a foreign child's labels are never read`() = runTest {
        val foreignLabel = view(cls = "android.widget.TextView", text = "Sponsored", packageName = "com.example.other")
        val row = view(clickable = true, bounds = Rect(36, 1374, 1044, 1500), children = listOf(
            view(cls = "android.widget.TextView", text = "This offer"), view(desc = "Expand"), foreignLabel,
        ))
        assertTrue(expand(handler(windowRoot(row))))
        row.clicks(1)
        verify(foreignLabel, never()).text
        verify(foreignLabel, never()).contentDescription
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

    /** R5: strategy 3 is bounded too — a hostile childCount under a hint-less ref stops at the budget, no click. */
    @Test
    fun `the bounds walk is bounded by the tree budget`() = runTest {
        val root = windowRoot()
        whenever(root.childCount).thenReturn(Int.MAX_VALUE)
        val legacy = expandRef.copy(labelHintHashes = emptyList(), labelHintsComplete = false)
        assertFalse(expand(handler(root), legacy))
        verify(root, org.mockito.kotlin.atMost(TreeLimits.MAX_TREE_NODES)).getChild(any())
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
}
