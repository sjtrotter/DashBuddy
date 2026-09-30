package cloud.trotter.dashbuddy.util

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.atMost
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * #1149 — [AccNodeUtils.resolveActionOwner] (the one "what does a tap land on" rule) and the
 * refresh-before-dispatch contract of [AccNodeUtils.clickNodeStrict].
 */
@RunWith(RobolectricTestRunner::class)
class AccNodeUtilsOwnerTest {

    private fun node(clickable: Boolean = false, advertisesClick: Boolean = false, parent: AccessibilityNodeInfo? = null): AccessibilityNodeInfo {
        val n = mock<AccessibilityNodeInfo>()
        whenever(n.isClickable).thenReturn(clickable)
        whenever(n.actionList).thenReturn(
            if (advertisesClick) listOf(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) else emptyList(),
        )
        whenever(n.parent).thenReturn(parent)
        return n
    }

    /** A chain of [length] non-clickable nodes above a leaf, with a clickable node on top. Returns (leaf, top). */
    private fun chain(length: Int): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        val top = node(clickable = true)
        var current = top
        repeat(length) { current = node(parent = current) }
        return current to top
    }

    @Test
    fun `a clickable node is its own owner`() {
        val n = node(clickable = true, parent = node(clickable = true))
        assertSame(n, AccNodeUtils.resolveActionOwner(n))
    }

    @Test
    fun `a non-clickable child resolves to its clickable parent`() {
        val parent = node(clickable = true)
        val child = node(parent = parent)
        assertSame(parent, AccNodeUtils.resolveActionOwner(child))
    }

    @Test
    fun `a parent that only ADVERTISES ACTION_CLICK is the owner`() {
        val parent = node(clickable = false, advertisesClick = true)
        val child = node(parent = parent)
        assertSame(parent, AccNodeUtils.resolveActionOwner(child))
    }

    @Test
    fun `a parent cycle terminates on the cycle guard and yields no owner`() {
        val a = mock<AccessibilityNodeInfo>()
        val b = mock<AccessibilityNodeInfo>()
        whenever(a.isClickable).thenReturn(false)
        whenever(b.isClickable).thenReturn(false)
        whenever(a.actionList).thenReturn(emptyList())
        whenever(b.actionList).thenReturn(emptyList())
        whenever(a.parent).thenReturn(b)
        whenever(b.parent).thenReturn(a)
        val leaf = node(parent = a)

        assertNull(AccNodeUtils.resolveActionOwner(leaf))
        // The cycle is detected on the revisit — not after burning the whole step budget.
        verify(a, atMost(1)).parent
        verify(b, atMost(1)).parent
    }

    @Test
    fun `the owner walk is bounded at MAX_OWNER_WALK steps`() {
        val (reachableLeaf, reachableTop) = chain(AccNodeUtils.MAX_OWNER_WALK - 1)
        assertSame("an owner 31 hops up is inside the bound", reachableTop, AccNodeUtils.resolveActionOwner(reachableLeaf))

        val (farLeaf, _) = chain(AccNodeUtils.MAX_OWNER_WALK)
        assertNull("an owner 32 hops up is outside the bound — no owner (fail closed)", AccNodeUtils.resolveActionOwner(farLeaf))
    }

    @Test
    fun `no clickable ancestor at all yields no owner`() {
        assertNull(AccNodeUtils.resolveActionOwner(node(parent = node())))
        assertNull(AccNodeUtils.resolveActionOwner(null))
    }

    @Test
    fun `clickNodeStrict does not dispatch to a node whose refresh fails`() {
        val owner = node(clickable = true)
        whenever(owner.refresh()).thenReturn(false)
        whenever(owner.performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))).thenReturn(true)

        assertFalse(AccNodeUtils.clickNodeStrict(owner))
        verify(owner, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test
    fun `clickNodeStrict refreshes and then clicks the owner itself`() {
        val parent = node(clickable = true)
        val owner = node(clickable = true, parent = parent)
        whenever(owner.refresh()).thenReturn(true)
        whenever(owner.performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))).thenReturn(true)

        assertTrue(AccNodeUtils.clickNodeStrict(owner))
        verify(owner, times(1)).refresh()
        verify(owner, times(1)).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
        verify(parent, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test
    fun `clickNodeStrict refuses a refreshed node that no longer takes a click — and never climbs`() {
        val parent = node(clickable = true)
        val stale = node(clickable = false, parent = parent)
        whenever(stale.refresh()).thenReturn(true)

        assertFalse(AccNodeUtils.clickNodeStrict(stale))
        verify(stale, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
        verify(parent, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
