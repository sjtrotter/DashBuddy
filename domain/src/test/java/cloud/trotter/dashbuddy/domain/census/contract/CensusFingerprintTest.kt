package cloud.trotter.dashbuddy.domain.census.contract

import cloud.trotter.dashbuddy.domain.model.accessibility.AnonymousWrappers
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
    fun `the lone-surrogate collision pair is refused, not encoded (review BB1)`() {
        // toByteArray(UTF_8) replaces an unpaired surrogate with '?', so these two classes encode to
        // identical bytes — the pair is kept out of the byte form by refusing malformed UTF-16.
        val lone = CensusFingerprint.Shape("", null, listOf(CensusFingerprint.Shape("\uD800", null, emptyList())))
        val question = CensusFingerprint.Shape("", null, listOf(CensusFingerprint.Shape("?", null, emptyList())))
        assertTrue(CensusFingerprint.canonicalBytes(lone).contentEquals(CensusFingerprint.canonicalBytes(question)))
        assertTrue(!WireStrings.isWellFormed("\uD800"))
        assertTrue(WireStrings.isWellFormed("?"))
        listOf(
            { UiSkeletonNodeDto(className = "\uD800") },
            { UiSkeletonNodeDto(className = "a\uDC00b") },
            { UiSkeletonNodeDto(id = "x:id/\uD800") },
        ).forEach { make ->
            try {
                make(); fail("malformed UTF-16 must be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            SkeletonSchema.json.decodeFromString(UiSkeletonNodeDto.serializer(), "{\"class\":\"\\ud800\"}")
            fail("malformed UTF-16 must be refused on decode")
        } catch (_: IllegalArgumentException) {
        }
        // A well-formed supplementary-plane character is well-formed UTF-16 (the class GRAMMAR is a
        // separate, later gate).
        assertTrue(WireStrings.isWellFormed("\uD801\uDC00"))
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

    @Test
    fun `the byte function distinguishes a null id from an empty one (review II2 moved this off the DTO)`() {
        fun root(id: String?) = CensusFingerprint.Shape("", null, listOf(CensusFingerprint.Shape("android.widget.TextView", id, emptyList())))
        val nullId = CensusFingerprint.canonicalBytes(root(null))
        val emptyId = CensusFingerprint.canonicalBytes(root(""))
        assertTrue(!nullId.contentEquals(emptyId))
        // Independent Python vectors (the pre-II2 pinned values).
        assertEquals("b953104254b69ae14bff2b0ebbc5bdabff72faad70068fc7ea49a3b7aad0b340", sha256OrNull(nullId))
        assertEquals("a91614370e3ff0e734b49b46533e149941518e42a7062916f2eb80663bf91517", sha256OrNull(emptyId))
        // UTF-8 byte lengths: a non-ASCII id can no longer enter a DTO (the static grammar is ASCII), but
        // the byte function still defines it (independent Python vector).
        val utf8 = CensusFingerprint.canonicalBytes(root("com.example:id/caf\u00e9"))
        assertEquals("03c247acf1220e3284014331a15827779ffb289e8f9dbc4168296fcdc61c0ef2", sha256OrNull(utf8))
    }

    @Test
    fun `the DTO refuses a non-static class or id shape, at construction and on decode (review II2)`() {
        listOf(
            { UiSkeletonNodeDto(id = "") },
            { UiSkeletonNodeDto(id = "PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a") },
            { UiSkeletonNodeDto(className = "Jane Smith's button") },
            { UiSkeletonNodeDto(className = "Composable_3f488d4a") },
        ).forEach { make ->
            try {
                make(); fail("a non-static class/id must be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            SkeletonSchema.json.decodeFromString(UiSkeletonNodeDto.serializer(), "{\"id\":\"row_1234\"}")
            fail("a non-static id must be refused on decode")
        } catch (_: IllegalArgumentException) {
        }
    }
}
