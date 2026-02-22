package cloud.trotter.dashbuddy.pipeline.accessibility.model

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UiNodeTest {

    // A helper to generate a fully-populated base node for testing
    private fun createBaseNode(): UiNode {
        return UiNode(
            text = "Accept",
            contentDescription = "Accept button",
            stateDescription = "expanded",
            viewIdResourceName = "com.doordash:id/accept_btn",
            className = "android.widget.Button",
            isClickable = true,
            isEnabled = true,
            isChecked = 0,
            boundsInScreen = Rect(10, 10, 100, 100)
        )
    }

    @Test
    fun `equals does NOT cause StackOverflowError on circular references`() {
        val parent1 = createBaseNode().copy(viewIdResourceName = "parent")
        val child1 = createBaseNode().copy(viewIdResourceName = "child")
        parent1.children.add(child1)
        child1.parent = parent1 // Circular Reference!

        val parent2 = createBaseNode().copy(viewIdResourceName = "parent")
        val child2 = createBaseNode().copy(viewIdResourceName = "child")
        parent2.children.add(child2)
        child2.parent = parent2 // Circular Reference!

        // THE TEST: If the bug exists, this will crash with StackOverflowError
        val areEqual = parent1 == parent2

        assertTrue("Nodes with the same core properties should be equal", areEqual)
    }

    @Test
    fun `equals returns true for identically populated nodes`() {
        val node1 = createBaseNode()
        val node2 = createBaseNode()

        assertEquals("Identical nodes should be equal", node1, node2)
    }

    @Test
    fun `equals returns false when any single text property differs`() {
        val base = createBaseNode()

        assertNotEquals("Should fail on text mismatch", base, base.copy(text = "Decline"))
        assertNotEquals(
            "Should fail on desc mismatch",
            base,
            base.copy(contentDescription = "Other")
        )
        assertNotEquals(
            "Should fail on state mismatch",
            base,
            base.copy(stateDescription = "collapsed")
        )
    }

    @Test
    fun `equals returns false when any single structure property differs`() {
        val base = createBaseNode()

        assertNotEquals(
            "Should fail on ID mismatch",
            base,
            base.copy(viewIdResourceName = "id/other")
        )
        assertNotEquals(
            "Should fail on class mismatch",
            base,
            base.copy(className = "android.widget.TextView")
        )
    }

    @Test
    fun `equals returns false when any single flag property differs`() {
        val base = createBaseNode()

        assertNotEquals("Should fail on clickable mismatch", base, base.copy(isClickable = false))
        assertNotEquals("Should fail on enabled mismatch", base, base.copy(isEnabled = false))
        assertNotEquals("Should fail on checked mismatch", base, base.copy(isChecked = 1))
    }

    @Test
    fun `equals returns false when bounds differ`() {
        val base = createBaseNode()
        val differentBounds = base.copy(boundsInScreen = Rect(0, 0, 0, 0))

        assertNotEquals("Should fail on bounds mismatch", base, differentBounds)
    }

    @Test
    fun `hashCode is consistent for identical nodes`() {
        val node1 = createBaseNode()
        val node2 = createBaseNode()

        assertEquals(
            "HashCodes must match if nodes are logically equal",
            node1.hashCode(),
            node2.hashCode()
        )
    }
}