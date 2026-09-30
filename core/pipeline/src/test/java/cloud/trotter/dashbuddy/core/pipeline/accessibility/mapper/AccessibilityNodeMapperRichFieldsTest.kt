package cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeInfo.CollectionInfo
import android.view.accessibility.AccessibilityNodeInfo.CollectionItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1147 — the TalkBack-study node fields: the mapper reads them off the live node, bounds every
 * string at [TreeBudget.MAX_TEXT_LENGTH], never lets them into `allText`, and never materializes the
 * action list for a node that advertises no click (the #1149 P6 cost stays off the common path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("DEPRECATION")
class AccessibilityNodeMapperRichFieldsTest {

    private val long = "x".repeat(TreeBudget.MAX_TEXT_LENGTH + 500)

    private fun richNode(clickLabel: String = "Accept offer"): AccessibilityNodeInfo {
        val m = mock<AccessibilityNodeInfo>()
        whenever(m.childCount).thenReturn(0)
        whenever(m.text).thenReturn("Accept")
        whenever(m.paneTitle).thenReturn("Offer sheet")
        whenever(m.extras).thenReturn(Bundle().apply { putCharSequence("AccessibilityNodeInfo.roleDescription", "Button") })
        whenever(m.hintText).thenReturn("Type here")
        whenever(m.tooltipText).thenReturn("Tip")
        whenever(m.error).thenReturn("Bad value")
        whenever(m.actions).thenReturn(AccessibilityNodeInfo.ACTION_CLICK)
        whenever(m.actionList).thenReturn(
            listOf(
                AccessibilityAction(AccessibilityNodeInfo.ACTION_FOCUS, "Focus it"),
                AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, clickLabel),
            ),
        )
        whenever(m.uniqueId).thenReturn("uid-1")
        whenever(m.isVisibleToUser).thenReturn(false)
        whenever(m.isFocusable).thenReturn(true)
        whenever(m.isScreenReaderFocusable).thenReturn(true)
        whenever(m.isCheckable).thenReturn(true)
        whenever(m.isSelected).thenReturn(true)
        whenever(m.isHeading).thenReturn(true)
        whenever(m.liveRegion).thenReturn(AccessibilityNodeInfo.ACCESSIBILITY_LIVE_REGION_POLITE)
        whenever(m.collectionInfo).thenReturn(CollectionInfo(4, 2, false))
        whenever(m.collectionItemInfo).thenReturn(CollectionItemInfo(3, 1, 1, 1, false))
        return m
    }

    @Test
    fun `every new field is read off the live node`() {
        val n = richNode().toUiNode()!!
        assertEquals("Offer sheet", n.paneTitle)
        assertEquals("Button", n.roleDescription)
        assertEquals("Type here", n.hintText)
        assertEquals("Tip", n.tooltipText)
        assertEquals("Bad value", n.errorText)
        assertEquals("the ACTION_CLICK entry's label, not the first action's", "Accept offer", n.clickActionLabel)
        assertEquals("uid-1", n.uniqueId)
        assertTrue(n.hasClickAction)
        assertFalse(n.isVisibleToUser)
        assertTrue(n.isFocusable)
        assertTrue(n.isScreenReaderFocusable)
        assertTrue(n.isCheckable)
        assertTrue(n.isSelected)
        assertTrue(n.isHeading)
        assertEquals(1, n.liveRegion)
        assertEquals(4, n.collectionRows)
        assertEquals(2, n.collectionCols)
        assertEquals(3, n.itemRow)
        assertEquals(1, n.itemCol)
    }

    @Test
    fun `the new strings stay out of allText - recognition sees text and desc only`() {
        val n = richNode().toUiNode()!!
        assertEquals(listOf("Accept"), n.allText)
        assertTrue(n.allScrubbableText().containsAll(listOf("Offer sheet", "Button", "Accept offer", "uid-1")))
    }

    @Test
    fun `every new string is capped at MAX_TEXT_LENGTH`() {
        val m = richNode(clickLabel = long)
        whenever(m.paneTitle).thenReturn(long)
        whenever(m.extras).thenReturn(Bundle().apply { putCharSequence("AccessibilityNodeInfo.roleDescription", long) })
        whenever(m.hintText).thenReturn(long)
        whenever(m.tooltipText).thenReturn(long)
        whenever(m.error).thenReturn(long)
        whenever(m.uniqueId).thenReturn(long)
        val n = m.toUiNode()!!
        for ((field, value) in n.scrubbableStrings()) {
            value?.let { assertTrue("$field is ${it.length}", it.length <= TreeBudget.MAX_TEXT_LENGTH) }
        }
        assertEquals(TreeBudget.MAX_TEXT_LENGTH, n.paneTitle!!.length)
        assertEquals(TreeBudget.MAX_TEXT_LENGTH, n.clickActionLabel!!.length)
    }

    @Test
    fun `a node advertising no click never materializes its action list`() {
        val m = mock<AccessibilityNodeInfo>()
        whenever(m.childCount).thenReturn(0)
        whenever(m.actions).thenReturn(AccessibilityNodeInfo.ACTION_FOCUS)
        val n = m.toUiNode()!!
        assertNull(n.clickActionLabel)
        verify(m, never()).actionList
    }

    @Test
    fun `a bare node maps to the dominant defaults and a throwing extras bundle costs only the role`() {
        val m = mock<AccessibilityNodeInfo>()
        whenever(m.childCount).thenReturn(0)
        whenever(m.isVisibleToUser).thenReturn(true)
        whenever(m.extras).thenThrow(RuntimeException("hostile bundle"))
        val n = m.toUiNode()!!
        assertNull(n.roleDescription)
        assertNull(n.paneTitle)
        assertTrue(n.isVisibleToUser)
        assertEquals(-1, n.collectionRows)
        assertEquals(-1, n.itemRow)
        assertEquals(0, n.liveRegion)
    }
}
