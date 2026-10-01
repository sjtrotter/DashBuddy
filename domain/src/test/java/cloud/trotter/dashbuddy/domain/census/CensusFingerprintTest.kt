package cloud.trotter.dashbuddy.domain.census

import cloud.trotter.census.contract.AnonymousWrappers
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1173 — the domain-side wrapper integration; encoder vectors live in the contract build. */
class CensusFingerprintTest {

    @Test
    fun `the wrapper class set is the one constant UiNode's stableHash reads`() {
        assertEquals(
            setOf(
                "android.view.View",
                "android.view.ViewGroup",
                "android.widget.FrameLayout",
                "android.widget.LinearLayout",
            ),
            AnonymousWrappers.WRAPPER_CLASSES,
        )
        // stableHash's own algorithm is unchanged: an anonymous wrapper folds its children from a
        // zero seed (never its own class/id), and an id-bearing FrameLayout is never a wrapper.
        val child = UiNode(className = "android.widget.TextView", viewIdResourceName = "x:id/t")
        val wrapped = UiNode(className = "android.widget.FrameLayout", children = listOf(child))
        assertEquals(31 * 0 + child.stableHash, wrapped.stableHash)
        val named = UiNode(className = "android.widget.FrameLayout", viewIdResourceName = "x:id/f", children = listOf(child))
        assertNotEquals(wrapped.stableHash, named.stableHash)
        assertTrue(AnonymousWrappers.isAnonymousWrapper("android.widget.FrameLayout", null))
        assertTrue(!AnonymousWrappers.isAnonymousWrapper("android.widget.FrameLayout", ""))
        assertTrue(!AnonymousWrappers.isAnonymousWrapper(null, null))
    }
}
