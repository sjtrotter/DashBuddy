package cloud.trotter.dashbuddy.domain.model.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #363 — the lazy hash/text caches assume a frozen tree. With children now an
 * immutable List built at construction, hashes must be identical across
 * (a) repeated access, (b) interleaved use of other lazy caches, and
 * (c) independent re-parses of the same structure.
 */
class UiNodeImmutabilityTest {

    private fun tree(): UiNode = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            UiNode(
                viewIdResourceName = "app:id/title",
                className = "android.widget.TextView",
                text = "Pickup from Wendy's",
            ),
            UiNode(
                className = "android.view.View", // anonymous wrapper
                children = listOf(
                    UiNode(
                        viewIdResourceName = "app:id/pay",
                        className = "android.widget.TextView",
                        text = "$7.50",
                    ),
                ),
            ),
        ),
    ).restoreParents()

    @Test
    fun `hashes are stable across repeated access and other lazy caches`() {
        val node = tree()
        val first = Triple(node.stableHash, node.structuralHash, node.contentHash)
        // Interleave the other lazy caches — they must not perturb anything.
        assertEquals(listOf("Pickup from Wendy's", "$7.50"), node.allText)
        val second = Triple(node.stableHash, node.structuralHash, node.contentHash)
        assertEquals(first, second)
    }

    @Test
    fun `independent constructions of the same structure hash identically`() {
        assertEquals(tree().stableHash, tree().stableHash)
        assertEquals(tree().structuralHash, tree().structuralHash)
        assertEquals(tree().contentHash, tree().contentHash)
    }

    @Test
    fun `stableHash ignores anonymous wrappers, structuralHash does not`() {
        val withWrapper = tree()
        val withoutWrapper = UiNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                withWrapper.children[0],
                UiNode(
                    viewIdResourceName = "app:id/pay",
                    className = "android.widget.TextView",
                    text = "$7.50",
                ),
            ),
        ).restoreParents()

        assertEquals(withWrapper.stableHash, withoutWrapper.stableHash)
        // structuralHash IS wrapper-sensitive by design.
        assert(withWrapper.structuralHash != withoutWrapper.structuralHash)
    }

    @Test
    fun `restoreParents wires every level and is idempotent`() {
        val root = tree()
        val wrapper = root.children[1]
        val pay = wrapper.children[0]
        assertSame(root, wrapper.parent)
        assertSame(wrapper, pay.parent)

        root.restoreParents() // second call must be harmless
        assertSame(root, wrapper.parent)
        assertSame(wrapper, pay.parent)
    }

    @Test
    fun `parent is excluded from equality so wired and unwired trees compare equal`() {
        val wired = tree()
        val unwired = UiNode(
            className = "android.widget.FrameLayout",
            children = wired.children,
        )
        assertEquals(wired, unwired)
    }
}
