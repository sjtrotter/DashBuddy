package cloud.trotter.dashbuddy.domain.census.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1160 reviews AF5, AF6, AF7 — the fold's single-pass FORMAT flag, the dash predicate, the fixed-point fast path. */
class TextFoldTest {

    @Test
    fun `the preserving fold reports a supplementary FORMAT code point from its own pass`() {
        val tagged = TextFold.foldGlyphsPreservingSupplementaryFlagged("vis󠀠a")
        assertTrue(tagged.hasSupplementaryFormat)
        assertEquals(TextFold.foldGlyphsPreservingSupplementary("vis󠀠a"), tagged.text)
        assertEquals(TextFold.hasSupplementaryFormat(tagged.text), tagged.hasSupplementaryFormat)
        listOf("visa", "a‍b", "🚗 car").forEach {
            val folded = TextFold.foldGlyphsPreservingSupplementaryFlagged(it)
            assertFalse(it, folded.hasSupplementaryFormat)
            assertEquals(it, TextFold.hasSupplementaryFormat(folded.text), folded.hasSupplementaryFormat)
        }
    }

    @Test
    fun `the flag is judged on the emitted output - a dropped BMP FORMAT char can join a pair (review AG1)`() {
        val split = TextFold.foldGlyphsPreservingSupplementaryFlagged("vi\uDB40\u200D\uDC20sa")
        assertEquals("vi\uDB40\uDC20sa", split.text)
        assertTrue(split.hasSupplementaryFormat)
    }

    @Test
    fun `one dash predicate`() {
        ('‐'..'―').forEach { assertTrue(TextFold.isFoldableDash(it)) }
        assertTrue(TextFold.isFoldableDash('−'))
        assertFalse(TextFold.isFoldableDash('-'))
        assertEquals("a-b-c", TextFold.foldForCensus("a–b−c"))
    }

    @Test
    fun `an already-canonical value is its own canonical form`() {
        listOf("Accept", "Pick up order", "a-b").forEach { assertEquals(it, CensusHash.canonical(it)) }
        assertEquals("Pick up", CensusHash.canonical("  Pick  up "))
    }
}
