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
import org.robolectric.RobolectricTestRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #734 — the actuation tie must ABORT, not "click first".
 *
 * When more than one live node survives label verification and the
 * [ClickCandidateRanker] cannot decide ([ClickCandidateRanker.Tier.UNRESOLVED]),
 * [UiInteractionHandler.performVerifiedClick] now refuses to click — matching the
 * empty-candidate and label-fail arms, consistent with #425's fail-closed
 * posture. The single-candidate path must still click (the ordering constraint:
 * tightening the predicate first is what keeps the decisive path reachable).
 *
 * Robolectric provides real `Rect`/`AccessibilityNodeInfo` support so the
 * handler's bounds bookkeeping (`Rect().toBoundingBox()`) runs; the candidate
 * nodes are Mockito mocks with stubbed labels/bounds/click results.
 */
@RunWith(RobolectricTestRunner::class)
class UiInteractionHandlerTieTest {

    private val pkg = "com.doordash.driverapp"

    /** A mocked live button whose label subtree is just its own [label]. */
    private fun button(label: String, id: String, bounds: Rect, clickResult: Boolean = true): AccessibilityNodeInfo {
        val node = mock<AccessibilityNodeInfo>()
        whenever(node.text).thenReturn(label)
        whenever(node.contentDescription).thenReturn(null)
        whenever(node.childCount).thenReturn(0)
        whenever(node.isClickable).thenReturn(true)
        whenever(node.parent).thenReturn(null)
        whenever(node.getBoundsInScreen(any())).thenAnswer { (it.arguments[0] as Rect).set(bounds) }
        whenever(node.performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))).thenReturn(clickResult)
        return node
    }

    private fun root(viewId: String, matches: List<AccessibilityNodeInfo>): AccessibilityNodeInfo {
        val root = mock<AccessibilityNodeInfo>()
        whenever(root.packageName).thenReturn(pkg)
        whenever(root.findAccessibilityNodeInfosByViewId(eq(viewId))).thenReturn(matches)
        return root
    }

    private fun handler(root: AccessibilityNodeInfo): UiInteractionHandler {
        val source = mock<AccessibilitySource> { on { getLiveWindowRoots() } doReturn listOf(root) }
        return UiInteractionHandler(source)
    }

    /**
     * Multi-window handler: [roots] is the (active-first) live window list and
     * [activeRoot] is what `getLiveNativeRoot()` returns — the #788 active-window
     * scoping compares each candidate's source root against it.
     */
    private fun handler(
        roots: List<AccessibilityNodeInfo>,
        activeRoot: AccessibilityNodeInfo?,
    ): UiInteractionHandler {
        val source = mock<AccessibilitySource> {
            on { getLiveWindowRoots() } doReturn roots
            on { getLiveNativeRoot() } doReturn activeRoot
        }
        return UiInteractionHandler(source)
    }

    @Test
    fun `two verified candidates with no decisive match abort to manual (no click)`() = runTest {
        val viewId = "com.doordash.driverapp:id/textView_prism_button_title"
        // Both pass CONFIRM_DECLINE's \bdecline\b label check; neither overlaps the
        // pinned ref bounds and the ref has no text, so the ranker is UNRESOLVED.
        val a = button("Decline offer", viewId, Rect(40, 1600, 1000, 1720))
        val b = button("Decline offer", viewId, Rect(40, 2000, 1000, 2120))
        val handler = handler(root(viewId, listOf(a, b)))

        val ref = NodeRef(
            viewIdSuffix = viewId,
            text = null,
            classNameHint = null,
            boundsInScreen = BoundingBox(0, 0, 10, 10),
            pathFingerprint = "",
        )

        val clicked = handler.performVerifiedClick(
            ref = ref,
            expectedPackage = pkg,
            expectation = RuleAction.CONFIRM_DECLINE.verification,
            description = "confirm decline (tie)",
        )

        assertFalse("an ambiguous 2-candidate tie must NOT click", clicked)
        verify(a, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
        verify(b, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test
    fun `a single verified candidate still clicks`() = runTest {
        val viewId = "com.doordash.driverapp:id/textView_prism_button_title"
        val only = button("Decline offer", viewId, Rect(40, 2000, 1000, 2120))
        val handler = handler(root(viewId, listOf(only)))

        val ref = NodeRef(
            viewIdSuffix = viewId,
            text = "Decline offer",
            classNameHint = null,
            boundsInScreen = BoundingBox(40, 2000, 1000, 2120),
            pathFingerprint = "",
        )

        val clicked = handler.performVerifiedClick(
            ref = ref,
            expectedPackage = pkg,
            expectation = RuleAction.CONFIRM_DECLINE.verification,
            description = "confirm decline (single)",
        )

        assertTrue("the decisive single-candidate path must still click", clicked)
        verify(only, times(1)).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /**
     * #788 — the 07-16 field shape: two windows each carry a viewId-matching node
     * (the confirm sheet's "Decline offer" in the ACTIVE window, the offer popup's
     * bare "Decline" BEHIND it under the same `textView_prism_button_title` id).
     * Both survive `\bdecline\b` label verification, so pre-#788 they tied and the
     * handler aborted. Active-window scoping now drops the background twin, leaving
     * one decisive candidate that clicks — even though the ranker alone is
     * UNRESOLVED (no stored text, non-overlapping pinned bounds).
     */
    @Test
    fun `active-window candidate wins over an other-window twin (no abort)`() = runTest {
        val viewId = "com.doordash.driverapp:id/textView_prism_button_title"
        // Active window: the confirm sheet's real "Decline offer".
        val sheetButton = button("Decline offer", viewId, Rect(40, 2000, 1000, 2120))
        val activeRoot = root(viewId, listOf(sheetButton))
        // Lower (background) window: the offer popup's bare "Decline" under the SAME id.
        val popupDecline = button("Decline", viewId, Rect(40, 1600, 1000, 1720))
        val otherRoot = root(viewId, listOf(popupDecline))
        // getLiveWindowRoots lists the active window first (AccessibilitySource contract).
        val handler = handler(roots = listOf(activeRoot, otherRoot), activeRoot = activeRoot)

        // No stored text and non-overlapping pinned bounds → the ranker alone is
        // UNRESOLVED; ONLY active-window scoping makes this decisive.
        val ref = NodeRef(
            viewIdSuffix = viewId,
            text = null,
            classNameHint = null,
            boundsInScreen = BoundingBox(0, 0, 10, 10),
            pathFingerprint = "",
        )

        val clicked = handler.performVerifiedClick(
            ref = ref,
            expectedPackage = pkg,
            expectation = RuleAction.CONFIRM_DECLINE.verification,
            description = "confirm decline (multi-window)",
        )

        assertTrue("the active-window sheet button must resolve decisively", clicked)
        verify(sheetButton, times(1)).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
        verify(popupDecline, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /**
     * #788 — active-window scoping must NOT paper over genuine ambiguity: two
     * distinct verified candidates WITHIN the active window (no decisive ranker
     * tier) still fail closed, exactly as before.
     */
    @Test
    fun `two candidates in the active window still abort (fail closed)`() = runTest {
        val viewId = "com.doordash.driverapp:id/textView_prism_button_title"
        val a = button("Decline offer", viewId, Rect(40, 1600, 1000, 1720))
        val b = button("Decline offer", viewId, Rect(40, 2000, 1000, 2120))
        val activeRoot = root(viewId, listOf(a, b))
        val handler = handler(roots = listOf(activeRoot), activeRoot = activeRoot)

        val ref = NodeRef(
            viewIdSuffix = viewId,
            text = null,
            classNameHint = null,
            boundsInScreen = BoundingBox(0, 0, 10, 10),
            pathFingerprint = "",
        )

        val clicked = handler.performVerifiedClick(
            ref = ref,
            expectedPackage = pkg,
            expectation = RuleAction.CONFIRM_DECLINE.verification,
            description = "confirm decline (same-window tie)",
        )

        assertFalse("two active-window candidates with no decisive match must NOT click", clicked)
        verify(a, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
        verify(b, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
