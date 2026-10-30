package cloud.trotter.log.gig.domain.pipeline

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerWalkTest {
    /** Equality models live window/source identity, independent of the fetched object's state. */
    private data class Node(val source: Int) {
        var parent: Node? = null
        var foreign = false
        var clickable = false
    }

    private class Shape(private val liveIdentity: Boolean) : OwnerShape<Node> {
        var parentReads = 0
        val inspected = mutableListOf<Node>()
        val clickChecks = mutableListOf<Node>()

        override fun parent(n: Node): Node? {
            parentReads++
            return n.parent
        }
        override fun foreign(n: Node): Boolean {
            inspected.add(n)
            return n.foreign
        }
        override fun takesClick(n: Node): Boolean {
            clickChecks.add(n)
            return n.clickable
        }
        override fun same(a: Node, b: Node): Boolean = if (liveIdentity) a == b else a === b
    }

    @Test
    fun `null and a parentless non-owner yield no owner`() {
        for (live in listOf(false, true)) {
            val shape = Shape(live)
            assertNull(actionOwner(null, shape))
            assertEquals(0, shape.inspected.size)
            assertEquals(0, shape.parentReads)
            assertNull(actionOwner(Node(1), shape))
            assertEquals(1, shape.parentReads)
        }
        assertNull(actionOwner(UiNode(), UiOwnerShape))
    }

    @Test
    fun `self is returned unchanged without reading its parent`() {
        for (live in listOf(false, true)) {
            val shape = Shape(live)
            val self = Node(1).apply { clickable = true; parent = Node(2) }
            assertSame(self, actionOwner(self, shape))
            assertEquals(0, shape.parentReads)
        }
        val self = UiNode(isClickable = true)
        assertSame(self, actionOwner(self, UiOwnerShape))
    }

    @Test
    fun `the nearest action-only mapped parent owns the tap`() {
        val leaf = UiNode()
        val parent = UiNode(hasClickAction = true, children = listOf(leaf))
        UiNode(isClickable = true, children = listOf(parent)).restoreParents()

        assertFalse(parent.isClickable)
        assertSame(parent, actionOwner(leaf, UiOwnerShape))
    }

    @Test
    fun `attribute-equal mapped wrappers are distinct nodes and do not refuse a bind`() {
        val leaf = UiNode()
        val wrapper = UiNode(children = listOf(leaf))
        val owner = UiNode(isClickable = true, text = "Accept", children = listOf(wrapper)).restoreParents()

        assertEquals(leaf, wrapper)
        assertNotSame(leaf, wrapper)
        assertFalse(UiOwnerShape.same(leaf, wrapper))
        assertSame(owner, actionOwner(leaf, UiOwnerShape))
        assertFalse(NodeRef.bindHintsOf(leaf).refused)

        val lookalikeOwner = owner.copy()
        assertEquals(owner, lookalikeOwner)
        assertFalse(UiOwnerShape.same(owner, lookalikeOwner))
        assertSame(lookalikeOwner, actionOwner(lookalikeOwner, UiOwnerShape))
    }

    @Test
    fun `live aliases are the same node and the cycle guard precedes clickability`() {
        val first = Node(7)
        val alias = Node(7).apply { clickable = true }
        first.parent = alias
        assertEquals(first, alias)
        assertNotSame(first, alias)

        val live = Shape(liveIdentity = true)
        assertNull(actionOwner(first, live))
        assertEquals(1, live.parentReads)
        assertEquals(1, live.clickChecks.size)

        // Reference identity must allow these distinct objects to reach the second node.
        assertSame(alias, actionOwner(first, Shape(liveIdentity = false)))
    }

    @Test
    fun `self and multi-node cycles terminate on their first revisit`() {
        for (live in listOf(false, true)) {
            val self = Node(1).apply { parent = this }
            val selfShape = Shape(live)
            assertNull(actionOwner(self, selfShape))
            assertEquals(1, selfShape.parentReads)
            assertEquals(1, selfShape.clickChecks.size)

            val a = Node(1)
            val b = Node(2)
            a.parent = b
            b.parent = a
            val shape = Shape(live)
            assertNull(actionOwner(a, shape))
            assertEquals(2, shape.parentReads)
            assertEquals(2, shape.clickChecks.size)
        }
    }

    @Test
    fun `31 hops are reachable but 32 and 33 are outside the inspection and parent-read bounds`() {
        for (hops in listOf(31, 32, 33)) {
            for (live in listOf(false, true)) {
                val top = Node(0).apply { clickable = true }
                var leaf = top
                repeat(hops) { index -> leaf = Node(index + 1).apply { parent = leaf } }
                val shape = Shape(live)
                val found = actionOwner(leaf, shape)
                if (hops == 31) assertSame(top, found) else assertNull(found)
                assertEquals("hops=$hops, live=$live", 32, shape.inspected.size)
                // #1424: the unused parent read after the last inspected node was removed.
                assertEquals("hops=$hops, live=$live", 31, shape.parentReads)
            }

            val leaf = UiNode()
            var child = leaf
            repeat(hops - 1) { child = UiNode(children = listOf(child)) }
            val top = UiNode(isClickable = true, children = listOf(child)).restoreParents()
            val found = actionOwner(leaf, UiOwnerShape)
            if (hops == 31) assertSame(top, found) else assertNull(found)
        }
    }

    @Test
    fun `foreign self and foreign hops refuse before click checks or parent reads`() {
        for (live in listOf(false, true)) {
            val top = Node(1).apply { clickable = true }
            val foreign = Node(2).apply { this.foreign = true; clickable = true; parent = top }
            val selfShape = Shape(live)
            assertNull(actionOwner(foreign, selfShape))
            assertEquals(0, selfShape.parentReads)
            assertTrue(selfShape.clickChecks.isEmpty())

            val leaf = Node(3).apply { parent = foreign }
            val hopShape = Shape(live)
            assertNull(actionOwner(leaf, hopShape))
            assertEquals(1, hopShape.parentReads)
            assertEquals(1, hopShape.clickChecks.size)
        }

        val leaf = UiNode()
        val foreign = UiNode(foreignPackage = true, isClickable = true, children = listOf(leaf))
        UiNode(isClickable = true, children = listOf(foreign)).restoreParents()
        assertNull(actionOwner(foreign, UiOwnerShape))
        assertNull(actionOwner(leaf, UiOwnerShape))
    }
}
