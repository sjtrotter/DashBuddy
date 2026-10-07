package cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@Suppress("DEPRECATION")
class AccessibilityNodeMapperRecyclingTest {
    @Test
    fun `normal mapping keeps tree and recycles child but leaves root to caller`() {
        val child = node()
        whenever(child.text).thenReturn("Offer")
        val root = node(child)
        val tree = root.toUiNode()!!
        assertEquals("Offer", tree.children.single().text)
        assertEquals(0, tree.unreadableChildren)
        verify(child).recycle()
        verify(root, never()).recycle()
    }

    @Test
    fun `depth rejected child is recycled and kept tree reports unreadable child`() {
        val rejected = node()
        var root = rejected
        repeat(TreeLimits.MAX_TREE_DEPTH + 1) { root = node(root) }
        var tree = root.toUiNode()!!
        repeat(TreeLimits.MAX_TREE_DEPTH) { tree = tree.children.single() }
        assertTrue(tree.children.isEmpty())
        assertEquals(1, tree.unreadableChildren)
        verify(rejected).recycle()
        verify(root, never()).recycle()
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

    private fun node(child: AccessibilityNodeInfo? = null): AccessibilityNodeInfo {
        val node = mock<AccessibilityNodeInfo>()
        if (child != null) {
            whenever(node.childCount).thenReturn(1)
            whenever(node.getChild(0)).thenReturn(child)
        }
        return node
    }
}
