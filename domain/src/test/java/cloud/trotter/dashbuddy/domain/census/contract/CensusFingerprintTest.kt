package cloud.trotter.dashbuddy.domain.census.contract

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.util.sha256OrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        // "C" "0" 0x00 (empty class, length-prefixed) "N" "1" 0x00 — the synthetic root over one child.
        val head = byteArrayOf('C'.code.toByte(), '0'.code.toByte(), 0, 'N'.code.toByte(), '1'.code.toByte(), 0)
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

    @Test
    fun `the framing-mimic collision pair of the delimiter-only form now differs (review AA2)`() {
        // Under the pre-AA2 encoding (class/id delimited by 0x00 with no length) these two synthetic
        // roots produced IDENTICAL bytes (verified with the independent Python encoder:
        // 90926beb5f27c7b9…). Length-prefixing makes the form prefix-free.
        val a = CensusFingerprint.Shape("", null, listOf(CensusFingerprint.Shape("a", null, listOf(CensusFingerprint.Shape("b", null, emptyList())))))
        val b = CensusFingerprint.Shape("", null, listOf(CensusFingerprint.Shape("a\u0000N\u00001\u0000Cb", null, emptyList())))
        val bytesA = CensusFingerprint.canonicalBytes(a)
        val bytesB = CensusFingerprint.canonicalBytes(b)
        assertTrue(!bytesA.contentEquals(bytesB))
        assertEquals("8c5e080aab010cf2d59f42f256821921ee1172abbb14b047faa7ff2b9cef6696", sha256OrNull(bytesA))
        assertEquals("82abbadab59d45614619d1cfbb5c84a0b8a7e07c2248d46792675b6eb4a6d707", sha256OrNull(bytesB))
    }

    @Test
    fun `a class or id carrying U+0000 is refused at construction and on decode`() {
        listOf(
            { UiSkeletonNodeDto(className = "a\u0000b") },
            { UiSkeletonNodeDto(id = "x:id/a\u0000") },
        ).forEach { make ->
            try {
                make(); fail("a NUL must be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            SkeletonSchema.json.decodeFromString(UiSkeletonNodeDto.serializer(), "{\"class\":\"a\\u0000b\"}")
            fail("a NUL must be refused on decode")
        } catch (_: IllegalArgumentException) {
        }
    }
}
