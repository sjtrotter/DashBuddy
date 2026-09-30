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
 * #1149 — owner-first resolution: one owner per control, a container never borrows a nested control's label, owner/evidence refresh BEFORE verification, the scoped (never-foreign) owner walk, and evidence labels (fresh, in-package, capped). Driven through [UiInteractionHandler.performVerifiedClick] over mocked live trees
 * ([UiInteractionHandlerTapTestKit]); every refusal path has a test that goes red if its guard is removed.
 */
@RunWith(RobolectricTestRunner::class)
class UiInteractionHandlerOwnerTest : UiInteractionHandlerTapTestKit() {

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

    /** An owner outside the scoped package is never a tap target. */
    @Test
    fun `a candidate whose owner belongs to another package is dropped`() = runTest {
        val title = view(cls = "android.widget.TextView", text = "Decline offer")
        val foreignButton = view(clickable = true, children = listOf(title), packageName = "com.example.other")
        val root = windowRoot(foreignButton, byId = listOf(title))
        assertFalse(confirmDecline(handler(root)))
        foreignButton.neverClicked()
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

    /** S3: evidence labels go through the cap — a 4 096-space + "Decline" title under an "Accept" owner is no Decline. */
    @Test
    fun `evidence labels are capped like every other label`() = runTest {
        val padded = " ".repeat(UiTextBounds.MAX_TEXT_LENGTH) + "Decline"
        val title = view(cls = "android.widget.TextView", text = padded)
        var chain: AccessibilityNodeInfo = title
        repeat(4) { chain = view(children = listOf(chain)) } // past the owner scan's depth
        val button = view(clickable = true, children = listOf(view(cls = "android.widget.TextView", text = "Accept"), chain))
        val root = windowRoot(button, byId = listOf(title))
        assertFalse(confirmDecline(handler(root)))
        button.neverClicked()
    }

    /** T5: a stale title must not win the evidence slot over a fresh sibling that carries the stored text. */
    @Test
    fun `evidence is chosen after refresh`() = runTest {
        val staleTitle = view(cls = "android.widget.TextView", text = "Decline offer", refreshes = false)
        val freshTitle = view(cls = "android.widget.TextView", text = "Decline offer")
        val button = view(clickable = true, children = listOf(staleTitle, freshTitle))
        val root = windowRoot(button, byId = listOf(staleTitle, freshTitle))
        assertTrue(confirmDecline(handler(root), idRef.copy(text = "Decline offer")))
        button.clicks(1)
    }

    /** U3: a hint-less exact bounds hit whose refresh rebinds it to "Continue dashing" elsewhere → dropped, no click. */
    @Test
    fun `a bounds hit that moves on refresh loses its geometric credit`() = runTest {
        var bounds = rowRect
        var label = "This offer"
        val row = view(clickable = true)
        whenever(row.getBoundsInScreen(any())).thenAnswer { (it.arguments[0] as Rect).set(bounds) }
        whenever(row.text).thenAnswer { label }
        whenever(row.refresh()).thenAnswer { bounds = Rect(36, 2200, 1044, 2300); label = "Continue dashing"; true }
        val legacy = expandRef.copy(labelHintHashes = emptyList(), labelHintsComplete = false)
        assertFalse(expand(handler(windowRoot(row)), legacy))
        row.neverClicked()
    }

    /**
     * W1: owner A has TWO members carrying the stored text — A1 far from the captured rect and the bound A2
     * exactly on it — while owner B's member overlaps the rect a little. A's evidence must be its STRONGEST
     * member (A2), so A wins strongest-vs-strongest; in BOTH child orders.
     */
    @Test
    fun `an owner's evidence is its strongest member in either child order`() = runTest {
        for (a2First in listOf(false, true)) {
            val a1 = view(cls = "android.widget.TextView", text = "Decline offer", bounds = Rect(40, 100, 1000, 220))
            val a2 = view(cls = "android.widget.TextView", text = "Decline offer", bounds = Rect(40, 2000, 1000, 2120))
            val ownerA = view(clickable = true, children = if (a2First) listOf(a2, a1) else listOf(a1, a2))
            val b1 = view(cls = "android.widget.TextView", text = "Decline offer", bounds = Rect(40, 2100, 1000, 2220))
            val ownerB = view(clickable = true, children = listOf(b1))
            val byId = if (a2First) listOf(a2, a1, b1) else listOf(a1, a2, b1)
            val root = windowRoot(ownerA, ownerB, byId = byId)
            val ref = idRef.copy(text = "Decline offer", boundsInScreen = BoundingBox(40, 2000, 1000, 2120))
            assertTrue("a2First=$a2First", confirmDecline(handler(root), ref))
            ownerA.clicks(1)
            ownerB.neverClicked()
        }
    }

    /**
     * X1: owner A = ["Decline" far, "Decline offer" @rect, "Decline offer" @rect] — the two strongest members
     * TIE inside A. A's evidence must be one of the tied-strongest, not the weak "Decline", so owner B's lone
     * "Decline offer" elsewhere does not become the sole exact-text match. Both enumeration orders → A.
     */
    @Test
    fun `an intra-owner tie keeps a tied-strongest representative`() = runTest {
        for (reversed in listOf(false, true)) {
            val weak = view(cls = "android.widget.TextView", text = "Decline", bounds = Rect(40, 100, 1000, 220))
            val t1 = view(cls = "android.widget.TextView", text = "Decline offer", bounds = Rect(40, 2000, 1000, 2120))
            val t2 = view(cls = "android.widget.TextView", text = "Decline offer", bounds = Rect(40, 2000, 1000, 2120))
            val members = listOf(weak, t1, t2).let { if (reversed) it.reversed() else it }
            val ownerA = view(clickable = true, children = members)
            val b1 = view(cls = "android.widget.TextView", text = "Decline offer", bounds = Rect(40, 600, 1000, 720))
            val ownerB = view(clickable = true, children = listOf(b1))
            val root = windowRoot(ownerA, ownerB, byId = members + b1)
            val ref = idRef.copy(text = "Decline offer", boundsInScreen = BoundingBox(40, 2000, 1000, 2120))
            assertTrue("reversed=$reversed", confirmDecline(handler(root), ref))
            ownerA.clicks(1)
            ownerB.neverClicked()
        }
    }

    private suspend fun accept(h: UiInteractionHandler, ref: NodeRef) = h.performVerifiedClick(
        ref = ref, expectedPackage = pkg, expectation = RuleAction.ACCEPT_OFFER.verification, description = "accept",
    )

    /** A bound non-clickable container four levels under its owner, its "Accept" on an immediate child. */
    private fun deepAcceptOwner(containerBounds: Rect): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        val container = view(bounds = containerBounds, children = listOf(view(cls = "android.widget.TextView", text = "Accept")))
        var chain: AccessibilityNodeInfo = container
        repeat(3) { chain = view(children = listOf(chain)) }
        val owner = view(clickable = true, children = listOf(chain))
        return owner to container
    }

    /** Y1: the matched container's own child label counts (its bounded subtree) — the lone deep container is clicked. */
    @Test
    fun `a deep bound container verifies through its own subtree`() = runTest {
        val rect = Rect(40, 2000, 1000, 2120)
        val (ownerA, container) = deepAcceptOwner(rect)
        val root = windowRoot(ownerA, byId = listOf(container))
        val ref = idRef.copy(boundsInScreen = BoundingBox(rect.left, rect.top, rect.right, rect.bottom))
        assertTrue(accept(handler(root), ref))
        ownerA.clicks(1)
    }

    /** Y1: with a competitor B sharing the id (readable "Accept", less overlap), A still wins — B is never clicked. */
    @Test
    fun `a deep bound container is not out-verified by a shallow competitor`() = runTest {
        val rect = Rect(40, 2000, 1000, 2120)
        val (ownerA, containerA) = deepAcceptOwner(rect)
        val containerB = view(bounds = Rect(40, 2100, 1000, 2220), children = listOf(view(cls = "android.widget.TextView", text = "Accept")))
        val ownerB = view(clickable = true, children = listOf(containerB))
        val root = windowRoot(ownerA, ownerB, byId = listOf(containerA, containerB))
        val ref = idRef.copy(boundsInScreen = BoundingBox(rect.left, rect.top, rect.right, rect.bottom))
        assertTrue(accept(handler(root), ref))
        ownerA.clicks(1)
        ownerB.neverClicked()
    }

    /**
     * Z1: owner A and its non-clickable descendant C share the id, null text and identical bounds; C (four
     * levels down) holds "Accept" on its child; competitor B shares the id with "Accept". The verification set is
     * the union over EVERY fresh member, so the evidence choice (A or C) never decides — A in both orders.
     */
    @Test
    fun `verification labels are the union over all fresh members`() = runTest {
        for (cFirst in listOf(false, true)) {
            val rect = Rect(40, 2000, 1000, 2120)
            val c = view(bounds = rect, children = listOf(view(cls = "android.widget.TextView", text = "Accept")))
            var chain: AccessibilityNodeInfo = c
            repeat(3) { chain = view(children = listOf(chain)) }
            val a = view(clickable = true, bounds = rect, children = listOf(chain))
            val bTitle = view(cls = "android.widget.TextView", text = "Accept")
            val b = view(clickable = true, bounds = Rect(40, 2100, 1000, 2220), children = listOf(bTitle))
            val byId = if (cFirst) listOf(c, a, b) else listOf(a, c, b)
            val root = windowRoot(a, b, byId = byId)
            val ref = idRef.copy(boundsInScreen = BoundingBox(rect.left, rect.top, rect.right, rect.bottom))
            assertTrue("cFirst=$cFirst", accept(handler(root), ref))
            a.clicks(1)
            b.neverClicked()
        }
    }
}
