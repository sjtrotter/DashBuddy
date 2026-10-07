package cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@Suppress("DEPRECATION")
class AccessibilityNodeMapperCompletenessTest {
    @Test
    fun `normal mapping keeps tree and recycles child but leaves root to caller`() {
        val child = node()
        whenever(child.text).thenReturn("Offer")
        val root = node(child)
        val mapping = root.toUiNodeMapping(maxDepth = 40, maxNodes = 1_500)
        assertTrue(mapping.complete)
        assertEquals("Offer", mapping.tree!!.children.single().text)
        assertEquals(root.toUiNode(), mapping.tree)
        // The second map above obtains and recycles the same mocked child again.
        verify(child, times(2)).recycle()
        verify(root, never()).recycle()
    }

    @Test
    fun `depth rejected child is recycled and truncation reported`() {
        val child = node()
        val mapping = node(child).toUiNodeMapping(maxDepth = 0, maxNodes = 10)
        assertTrue(mapping.truncated)
        assertFalse(mapping.nodesExhausted)
        assertFalse(mapping.complete)
        verify(child).recycle()
    }

    @Test
    fun `exception while reading child still recycles it`() {
        val child = node()
        whenever(child.childCount).thenThrow(IllegalStateException("unreadable"))
        val root = node(child)
        assertThrows(IllegalStateException::class.java) { root.toUiNode() }
        verify(child).recycle()
        verify(root, never()).recycle()
    }

    @Test
    fun `null grandchild makes whole tree incomplete`() {
        val child = node()
        whenever(child.childCount).thenReturn(1)
        val mapping = node(child).toUiNodeMapping(maxDepth = 40, maxNodes = 1_500)
        assertFalse(mapping.truncated)
        assertFalse(mapping.nodesExhausted)
        assertFalse(mapping.complete)
        assertEquals(1, mapping.tree!!.children.single().unreadableChildren)
    }

    @Test
    fun `exact node budget is incomplete even without truncation or unreadable children`() {
        val mapping = node().toUiNodeMapping(maxDepth = 40, maxNodes = 1)
        assertFalse(mapping.truncated)
        assertTrue(mapping.nodesExhausted)
        assertEquals(0, mapping.tree!!.unreadableChildren)
        assertFalse(mapping.complete)
    }

    @Test
    fun `null child fan respects the smaller mapping budget`() {
        val root = node()
        whenever(root.childCount).thenReturn(10_000)
        val mapping = root.toUiNodeMapping(maxDepth = 40, maxNodes = 1_500)
        assertFalse(mapping.complete)
        verify(root).getChild(1_499)
        verify(root, never()).getChild(1_500)
    }

    private fun node(child: AccessibilityNodeInfo? = null): AccessibilityNodeInfo {
        val node = mock<AccessibilityNodeInfo>()
        if (child != null) {
            whenever(node.childCount).thenReturn(1)
            whenever(node.getChild(0)).thenReturn(child)
        }
        return node
    }
}
