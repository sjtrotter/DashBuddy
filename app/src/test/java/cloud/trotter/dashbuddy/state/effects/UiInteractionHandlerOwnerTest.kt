package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import kotlinx.coroutines.test.runTest
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
    private fun view(
        cls: String = "android.view.View", clickable: Boolean = false, advertisesClick: Boolean = false,
        bounds: Rect = Rect(0, 0, 10, 10), text: String? = null, desc: String? = null,
        children: List<AccessibilityNodeInfo> = emptyList(), refreshes: Boolean = true,
    ): AccessibilityNodeInfo {
        val node = mock<AccessibilityNodeInfo>()
        whenever(node.className).thenReturn(cls)
        whenever(node.text).thenReturn(text)
        whenever(node.contentDescription).thenReturn(desc)
        whenever(node.isClickable).thenReturn(clickable)
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

    private fun windowRoot(vararg children: AccessibilityNodeInfo, byId: List<AccessibilityNodeInfo> = emptyList()): AccessibilityNodeInfo {
        val root = view(cls = "android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400), children = children.toList())
        whenever(root.packageName).thenReturn(pkg)
        whenever(root.findAccessibilityNodeInfosByViewId(eq(titleId))).thenReturn(byId)
        return root
    }

    private fun handler(root: AccessibilityNodeInfo): UiInteractionHandler {
        val source = mock<AccessibilitySource> {
            on { getLiveWindowRoots() } doReturn listOf(root)
            on { getLiveNativeRoot() } doReturn root
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

    /** A footer holding clickable Accept AND Decline is a container, not a control — refused. */
    @Test
    fun `a compound owner is refused with no click`() = runTest {
        val accept = view(clickable = true, children = listOf(view(cls = "android.widget.TextView", text = "Accept")))
        val decline = view(clickable = true, children = listOf(view(cls = "android.widget.TextView", text = "Decline")))
        // The bound node is a non-clickable label directly in the footer, so its owner is the footer.
        val label = view(cls = "android.widget.TextView", text = "Decline")
        val footer = view(clickable = true, bounds = Rect(0, 2000, 1080, 2200), children = listOf(accept, decline, label))
        val root = windowRoot(footer, byId = listOf(label))

        assertFalse(confirmDecline(handler(root)))
        footer.neverClicked(); accept.neverClicked(); decline.neverClicked()
    }

    /** The compound rule counts INDEPENDENT controls: the Decline button inside that same footer still clicks. */
    @Test
    fun `a button inside a compound footer is not itself compound and is clicked`() = runTest {
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

    /** A clickable wrapper around the slid row inherits its labels — undecidable, abort (the #1093 rule, now via 2b). */
    @Test
    fun `a wrapper around the slid row found by labels makes the pair undecidable — abort`() = runTest {
        val inner = payRow(top = 1774 - 400)
        val wrapper = view(clickable = true, bounds = Rect(20, 1360, 1060, 1520), children = listOf(inner))
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

    /** Bounded ingestion: a row deeper than SEMANTIC_SCAN_DEPTH is outside the 2b walk (and slid past the bounds walk). */
    @Test
    fun `the semantic walk is depth bounded`() = runTest {
        val row = payRow(top = 1774 - 400)
        var top: AccessibilityNodeInfo = row
        repeat(UiInteractionHandler.SEMANTIC_SCAN_DEPTH) { top = view(children = listOf(top)) }
        // row now sits at depth SEMANTIC_SCAN_DEPTH + 1 below the window root
        assertFalse(expand(handler(windowRoot(top))))
        row.neverClicked()

        // Control: the same row one level shallower IS found.
        val row2 = payRow(top = 1774 - 400)
        var top2: AccessibilityNodeInfo = row2
        repeat(UiInteractionHandler.SEMANTIC_SCAN_DEPTH - 1) { top2 = view(children = listOf(top2)) }
        assertTrue(expand(handler(windowRoot(top2))))
        row2.clicks(1)
    }

    /** Bounded ingestion: a row past SEMANTIC_SCAN_NODES visited nodes is outside the 2b walk. */
    @Test
    fun `the semantic walk is node-count bounded`() = runTest {
        val row = payRow(top = 1774 - 400)
        val filler = List(UiInteractionHandler.SEMANTIC_SCAN_NODES) { view() }
        assertFalse(expand(handler(windowRoot(*(filler + row).toTypedArray()))))
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
}
