package cloud.trotter.dashbuddy.domain.model.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity-based sibling walk (#1029 E2 / #860 / #886) — ONE owner for the three call sites
 * that must not be fooled by a structural twin.
 *
 * `UiNode.equals` compares a node's OWN identity fields and deliberately not its children, so two
 * wrapper `View`s differing only in what they contain are equal. [UiNode.sibling] resolves its
 * origin with `List.indexOf` — structural equality — so it starts from the FIRST equal node, which
 * on a flattened row is the wrong one. That is fine for the positional `sibling(N)` rule
 * vocabulary, which has always had those semantics, but not for a PII mask predicate (#860/#886)
 * or a capped money scan (#1029), so those go through [UiNode.followingSiblings] /
 * [UiNode.precedingSibling] instead.
 */
class UiNodeSiblingWalkTest {

    private val emptyTwin = UiNode(className = "Row")
    private val anchor = UiNode(className = "Row", children = listOf(UiNode(text = "Customer tips")))
    private val code = UiNode(text = "799")
    private val money = UiNode(text = "\$7.00")

    private val row = UiNode(
        className = "Sheet",
        children = listOf(emptyTwin, anchor, code, money),
    ).restoreParents()

    @Test
    fun `the twins really are equal — otherwise this test proves nothing`() {
        assertEquals("children are excluded from equals by design", emptyTwin, anchor)
        assertTrue("...but they are distinct instances", emptyTwin !== anchor)
    }

    @Test
    fun `followingSiblings starts after the node ITSELF, not after its equal twin`() {
        assertEquals(listOf(code, money), anchor.followingSiblings())
        assertSame(code, anchor.followingSiblings().first())
    }

    @Test
    fun `the structural helper resolves the twin's index — the reason this exists`() {
        // Characterization: `sibling(1)` off the anchor returns the ANCHOR (twin index 0, +1),
        // one slot early. A capped scan starting there would never reach the money node.
        assertSame(anchor, anchor.sibling(1))
    }

    @Test
    fun `precedingSibling is the same walk, mirrored`() {
        assertSame(anchor, code.precedingSibling())
        assertSame(emptyTwin, anchor.precedingSibling())
        assertNull("the first child has none", emptyTwin.precedingSibling())
    }

    @Test
    fun `the last child has no following siblings`() {
        assertEquals(emptyList<UiNode>(), money.followingSiblings())
    }

    @Test
    fun `an unwired tree yields nothing rather than guessing`() {
        // No `restoreParents()` — the mappers always call it, but a null parent must fail closed.
        val loose = UiNode(children = listOf(UiNode(text = "a"), UiNode(text = "b")))
        assertEquals(emptyList<UiNode>(), loose.children.first().followingSiblings())
        assertNull(loose.children.last().precedingSibling())
    }
}
