package cloud.trotter.dashbuddy.domain.model.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiNodeTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun node(
        text: String? = null,
        contentDescription: String? = null,
        viewIdResourceName: String? = null,
        className: String? = null,
        isClickable: Boolean = false,
        isEnabled: Boolean = true,
        boundsInScreen: BoundingBox = BoundingBox(0, 0, 100, 100),
        children: List<UiNode> = emptyList(),
    ): UiNode {
        val n = UiNode(
            text = text,
            contentDescription = contentDescription,
            viewIdResourceName = viewIdResourceName,
            className = className,
            isClickable = isClickable,
            isEnabled = isEnabled,
            boundsInScreen = boundsInScreen,
            children = children.toMutableList(),
        )
        n.restoreParents()
        return n
    }

    private fun tree(): UiNode {
        val grandchild = node(text = "Grandchild", viewIdResourceName = "com.app:id/grandchild_view")
        val child1 = node(text = "Child1", viewIdResourceName = "com.app:id/child_one", children = listOf(grandchild))
        val child2 = node(contentDescription = "Child2Desc", viewIdResourceName = "com.app:id/child_two", isClickable = true)
        return node(text = "Root", viewIdResourceName = "com.app:id/root", children = listOf(child1, child2))
    }

    // -------------------------------------------------------------------------
    // equals / hashCode
    // -------------------------------------------------------------------------

    @Test
    fun `equals - same field values are equal regardless of parent or children`() {
        val a = node(text = "Hello", viewIdResourceName = "com.app:id/view")
        val b = node(text = "Hello", viewIdResourceName = "com.app:id/view")
        assertEquals(a, b)
    }

    @Test
    fun `equals - different text are not equal`() {
        val a = node(text = "Hello")
        val b = node(text = "World")
        assertFalse(a == b)
    }

    @Test
    fun `equals - different viewIdResourceName are not equal`() {
        val a = node(viewIdResourceName = "com.app:id/foo")
        val b = node(viewIdResourceName = "com.app:id/bar")
        assertFalse(a == b)
    }

    @Test
    fun `equals - same node identity is equal to itself`() {
        val a = node(text = "Test")
        assertEquals(a, a)
    }

    @Test
    fun `equals - nodes with different children but same fields are equal`() {
        // equals intentionally excludes children and parent (avoids recursive comparison)
        val child = node(text = "Child")
        val a = node(text = "Parent", children = listOf(child))
        val b = node(text = "Parent", children = emptyList())
        assertEquals(a, b)
    }

    @Test
    fun `hashCode - equal nodes have equal hashCode`() {
        val a = node(text = "Hello", viewIdResourceName = "com.app:id/view")
        val b = node(text = "Hello", viewIdResourceName = "com.app:id/view")
        assertEquals(a.hashCode(), b.hashCode())
    }

    // -------------------------------------------------------------------------
    // restoreParents
    // -------------------------------------------------------------------------

    @Test
    fun `restoreParents - child nodes have parent set correctly`() {
        val root = tree()
        val child1 = root.children[0]
        assertEquals(root, child1.parent)
    }

    @Test
    fun `restoreParents - grandchild has correct parent`() {
        val root = tree()
        val grandchild = root.children[0].children[0]
        assertEquals(root.children[0], grandchild.parent)
    }

    @Test
    fun `restoreParents - root node has null parent`() {
        val root = tree()
        assertNull(root.parent)
    }

    // -------------------------------------------------------------------------
    // matchesId / matchesText / matchesDesc (strict - this node only)
    // -------------------------------------------------------------------------

    @Test
    fun `matchesId - suffix match is case-insensitive`() {
        val n = node(viewIdResourceName = "com.app:id/MyButton")
        assertTrue(n.matchesId("mybutton"))
        assertTrue(n.matchesId("MyButton"))
        assertFalse(n.matchesId("OtherButton"))
    }

    @Test
    fun `matchesId - null id returns false`() {
        val n = node(viewIdResourceName = null)
        assertFalse(n.matchesId("anything"))
    }

    @Test
    fun `matchesText - substring match is case-insensitive`() {
        val n = node(text = "Deliver by 8:52 PM")
        assertTrue(n.matchesText("deliver"))
        assertTrue(n.matchesText("8:52 PM"))
        assertFalse(n.matchesText("Accept"))
    }

    @Test
    fun `matchesText - null text returns false`() {
        val n = node(text = null)
        assertFalse(n.matchesText("anything"))
    }

    @Test
    fun `matchesDesc - substring match is case-insensitive`() {
        val n = node(contentDescription = "Navigate Up")
        assertTrue(n.matchesDesc("navigate"))
        assertFalse(n.matchesDesc("Accept"))
    }

    // -------------------------------------------------------------------------
    // hasId / hasText / hasContentDescription (recursive)
    // -------------------------------------------------------------------------

    @Test
    fun `hasId - finds id in grandchild`() {
        val root = tree()
        assertTrue(root.hasId("grandchild_view"))
    }

    @Test
    fun `hasId - returns false when id not in tree`() {
        val root = tree()
        assertFalse(root.hasId("nonexistent_id"))
    }

    @Test
    fun `hasText - finds text in child`() {
        val root = tree()
        assertTrue(root.hasText("Child1"))
    }

    @Test
    fun `hasText - finds text in grandchild`() {
        val root = tree()
        assertTrue(root.hasText("Grandchild"))
    }

    @Test
    fun `hasText - returns false when text not in tree`() {
        val root = tree()
        assertFalse(root.hasText("Nonexistent"))
    }

    @Test
    fun `hasContentDescription - finds desc in child`() {
        val root = tree()
        assertTrue(root.hasContentDescription("Child2Desc"))
    }

    // -------------------------------------------------------------------------
    // findNode / findNodes
    // -------------------------------------------------------------------------

    @Test
    fun `findNode - finds first match`() {
        val root = tree()
        val found = root.findNode { it.text == "Child1" }
        assertNotNull(found)
        assertEquals("Child1", found?.text)
    }

    @Test
    fun `findNode - returns null when no match`() {
        val root = tree()
        val found = root.findNode { it.text == "NoSuchNode" }
        assertNull(found)
    }

    @Test
    fun `findNodes - returns all matches`() {
        val child1 = node(text = "Foo")
        val child2 = node(text = "Foo")
        val root = node(text = "Root", children = listOf(child1, child2))
        val found = root.findNodes { it.text == "Foo" }
        assertEquals(2, found.size)
    }

    @Test
    fun `findNodes - returns empty list when no match`() {
        val root = tree()
        val found = root.findNodes { it.text == "NoSuchNode" }
        assertTrue(found.isEmpty())
    }

    // -------------------------------------------------------------------------
    // findDescendantById
    // -------------------------------------------------------------------------

    @Test
    fun `findDescendantById - finds self`() {
        val n = node(viewIdResourceName = "com.app:id/target")
        val found = n.findDescendantById("target")
        assertEquals(n, found)
    }

    @Test
    fun `findDescendantById - finds grandchild`() {
        val root = tree()
        val found = root.findDescendantById("grandchild_view")
        assertNotNull(found)
        assertEquals("Grandchild", found?.text)
    }

    @Test
    fun `findDescendantById - returns null when not found`() {
        val root = tree()
        assertNull(root.findDescendantById("does_not_exist"))
    }

    // -------------------------------------------------------------------------
    // findChildById
    // -------------------------------------------------------------------------

    @Test
    fun `findChildById - finds direct child only`() {
        val root = tree()
        val found = root.findChildById("child_one")
        assertNotNull(found)
        assertEquals("Child1", found?.text)
    }

    @Test
    fun `findChildById - does not find grandchild`() {
        val root = tree()
        val found = root.findChildById("grandchild_view")
        assertNull(found) // grandchild is not a direct child of root
    }

    // -------------------------------------------------------------------------
    // allText (lazy)
    // -------------------------------------------------------------------------

    @Test
    fun `allText - collects all text and contentDescriptions recursively`() {
        val root = tree()
        val texts = root.allText
        assertTrue(texts.contains("Root"))
        assertTrue(texts.contains("Child1"))
        assertTrue(texts.contains("Grandchild"))
        assertTrue(texts.contains("Child2Desc"))
    }

    @Test
    fun `allText - excludes blank and null values`() {
        val n = node(text = "  ", contentDescription = null)
        // Blank text should not be included
        assertTrue(n.allText.none { it.isBlank() })
    }

    @Test
    fun `allText - empty tree returns empty list`() {
        val n = node()
        assertTrue(n.allText.isEmpty())
    }

    // -------------------------------------------------------------------------
    // structuralHash / contentHash
    // -------------------------------------------------------------------------

    @Test
    fun `structuralHash - same structure has same hash`() {
        val a = node(className = "android.widget.TextView", viewIdResourceName = "com.app:id/view")
        val b = node(className = "android.widget.TextView", viewIdResourceName = "com.app:id/view")
        assertEquals(a.structuralHash, b.structuralHash)
    }

    @Test
    fun `contentHash - same content has same hash`() {
        val a = node(text = "Hello", contentDescription = "World")
        val b = node(text = "Hello", contentDescription = "World")
        assertEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `contentHash - different text yields different hash`() {
        val a = node(text = "Hello")
        val b = node(text = "World")
        assertFalse(a.contentHash == b.contentHash)
    }
}
