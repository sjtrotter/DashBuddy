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
        whenever(n.actions).thenReturn(if (advertisesClick) AccessibilityNodeInfo.ACTION_CLICK else 0) // P6: the bitmask
        whenever(n.actionList).thenReturn(
            if (advertisesClick) listOf(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) else emptyList(),
        )
        whenever(n.parent).thenReturn(parent)
        whenever(n.packageName).thenReturn(pkg)
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
        assertSame(n, AccNodeUtils.resolveActionOwner(n, pkg))
    }

    @Test
    fun `a non-clickable child resolves to its clickable parent`() {
        val parent = node(clickable = true)
        val child = node(parent = parent)
        assertSame(parent, AccNodeUtils.resolveActionOwner(child, pkg))
    }

    @Test
    fun `a parent that only ADVERTISES ACTION_CLICK is the owner`() {
        val parent = node(clickable = false, advertisesClick = true)
        val child = node(parent = parent)
        assertSame(parent, AccNodeUtils.resolveActionOwner(child, pkg))
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
        whenever(a.packageName).thenReturn(pkg)
        whenever(b.packageName).thenReturn(pkg)
        whenever(b.parent).thenReturn(a)
        val leaf = node(parent = a)

        assertNull(AccNodeUtils.resolveActionOwner(leaf, pkg))
        // The cycle is detected on the revisit — not after burning the whole step budget.
        verify(a, atMost(1)).parent
        verify(b, atMost(1)).parent
    }

    @Test
    fun `the owner walk is bounded at MAX_OWNER_WALK steps`() {
        val (reachableLeaf, reachableTop) = chain(AccNodeUtils.MAX_OWNER_WALK - 1)
        assertSame("an owner 31 hops up is inside the bound", reachableTop, AccNodeUtils.resolveActionOwner(reachableLeaf, pkg))

        val (farLeaf, _) = chain(AccNodeUtils.MAX_OWNER_WALK)
        assertNull("an owner 32 hops up is outside the bound — no owner (fail closed)", AccNodeUtils.resolveActionOwner(farLeaf, pkg))
    }

    @Test
    fun `no clickable ancestor at all yields no owner`() {
        assertNull(AccNodeUtils.resolveActionOwner(node(parent = node()), pkg))
        assertNull(AccNodeUtils.resolveActionOwner(null, pkg))
    }

    private val pkg = "com.doordash.driverapp"

    @Test
    fun `clickNodeStrict clicks the owner itself without refreshing it again`() {
        val parent = node(clickable = true)
        val owner = node(clickable = true, parent = parent)
        whenever(owner.packageName).thenReturn(pkg)
        whenever(owner.performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))).thenReturn(true)

        assertTrue(AccNodeUtils.clickNodeStrict(owner, pkg))
        verify(owner, never()).refresh() // #1149 review I1: the caller refreshed BEFORE verifying
        verify(owner, times(1)).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
        verify(parent, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test
    fun `clickNodeStrict refuses a node that no longer takes a click — and never climbs`() {
        val parent = node(clickable = true)
        val stale = node(clickable = false, parent = parent)
        whenever(stale.packageName).thenReturn(pkg)

        assertFalse(AccNodeUtils.clickNodeStrict(stale, pkg))
        verify(stale, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
        verify(parent, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test
    fun `clickNodeStrict refuses an owner outside the scoped package`() {
        val owner = node(clickable = true)
        whenever(owner.packageName).thenReturn("com.example.other")
        assertFalse(AccNodeUtils.clickNodeStrict(owner, pkg))
        verify(owner, never()).performAction(eq(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /**
     * #1149 review N4 — the bind-time owner walk (NodeRef.bindHintsOf over a UiNode chain) and the live
     * one (resolveActionOwner over a node chain) inspect the SAME nodes: self + (MAX_OWNER_WALK − 1)
     * parents. Paired at 31, 32 and 33 hops.
     */
    @Test
    fun `bind-time and live owner walks agree at 31, 32 and 33 hops`() {
        for (hops in listOf(AccNodeUtils.MAX_OWNER_WALK - 1, AccNodeUtils.MAX_OWNER_WALK, AccNodeUtils.MAX_OWNER_WALK + 1)) {
            val (leaf, top) = chain(hops)
            val liveFound = AccNodeUtils.resolveActionOwner(leaf, pkg) === top

            var ui = cloud.trotter.dashbuddy.domain.model.accessibility.UiNode(text = "This offer")
            val uiLeaf = ui
            repeat(hops - 1) { ui = cloud.trotter.dashbuddy.domain.model.accessibility.UiNode(children = listOf(ui)) }
            val uiTop = cloud.trotter.dashbuddy.domain.model.accessibility.UiNode(
                className = "Top", isClickable = true, children = listOf(ui),
            ).restoreParents()
            val bindFound = cloud.trotter.dashbuddy.domain.pipeline.NodeRef.bindHintsOf(uiLeaf).ownerClassHint == uiTop.className

            assertTrue("hops=$hops: bind and live must agree", liveFound == bindFound)
            assertTrue("hops=$hops: found iff within self + ${AccNodeUtils.MAX_OWNER_WALK - 1} parents",
                liveFound == (hops <= AccNodeUtils.MAX_OWNER_WALK - 1))
        }
    }
}
