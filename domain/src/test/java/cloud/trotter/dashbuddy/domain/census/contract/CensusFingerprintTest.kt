package cloud.trotter.dashbuddy.domain.census.contract

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §8 — the census cluster key against the shared (independently computed) vectors. */
class CensusFingerprintTest {

    @Test
    fun `every pinned vector matches the independent implementation`() {
        CensusFingerprintVectors.PINNED.forEach { v ->
            assertEquals(v.name, v.expectedHex, CensusFingerprint.of(v.tree))
        }
    }

    @Test
    fun `every structural relation holds`() {
        CensusFingerprintVectors.RELATIONS.forEach { r ->
            val left = CensusFingerprint.of(r.left)
            val right = CensusFingerprint.of(r.right)
            if (r.equal) assertEquals(r.name, left, right) else assertNotEquals(r.name, left, right)
        }
    }

    @Test
    fun `the fingerprint ignores text slots and flags - it is structure only`() {
        val bare = UiSkeletonNodeDto(className = "android.widget.TextView", id = "com.example:id/t")
        val dressed = bare.copy(
            isClickable = true,
            isChecked = 1,
            text = mapOf("text" to TextSlot(kind = KindClassifier.MIXED)),
        )
        assertEquals(CensusFingerprint.of(bare), CensusFingerprint.of(dressed))
    }

    @Test
    fun `the synthetic root carries the spliced count`() {
        val bytes = CensusFingerprint.canonicalBytes(CensusFingerprintVectors.WRAPPER_ROOT)
        // "C" "" 0x00 "N" 0x00 "1" 0x00 — the synthetic root over the one spliced child.
        val head = byteArrayOf('C'.code.toByte(), 0, 'N'.code.toByte(), 0, '1'.code.toByte(), 0)
        assertTrue(bytes.copyOfRange(0, head.size).contentEquals(head))
    }

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
