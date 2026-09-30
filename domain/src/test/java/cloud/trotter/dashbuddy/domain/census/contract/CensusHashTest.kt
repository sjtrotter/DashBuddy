package cloud.trotter.dashbuddy.domain.census.contract

import cloud.trotter.dashbuddy.domain.util.sha256OrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §3 — `sha256("census.v1:" + trimmed)`, first 16 hex, fail-closed. */
class CensusHashTest {

    @Test
    fun `hash is the domain-prefixed sha256 truncated to 16 hex`() {
        val h = CensusHash.of("Accept")!!
        assertEquals(sha256OrNull("census.v1:Accept")!!.substring(0, 16), h)
        assertEquals(16, h.length)
        assertTrue(CensusHash.isWellFormed(h))
    }

    @Test
    fun `the value is trimmed before hashing`() {
        assertEquals(CensusHash.of("Accept"), CensusHash.of("  Accept \n"))
    }

    @Test
    fun `the domain prefix separates the census digest from an unprefixed one`() {
        assertNotEquals(sha256OrNull("Accept")!!.substring(0, 16), CensusHash.of("Accept"))
    }

    @Test
    fun `a digest failure yields null, never the input`() {
        assertNull(CensusHash.of("Accept") { null })
        assertNull(CensusHash.of("Accept") { "abc" })
    }

    @Test
    fun `well-formedness is exactly 16 lowercase hex`() {
        assertTrue(CensusHash.isWellFormed("0123456789abcdef"))
        listOf("0123456789ABCDEF", "0123456789abcde", "0123456789abcdef0", "0123456789abcdeg").forEach {
            assertTrue(it, !CensusHash.isWellFormed(it))
        }
    }

    @Test
    fun `the canonical value collapses every census whitespace to one ASCII space (review EE2)`() {
        assertEquals("Pickup & delivery", CensusHash.canonical("  Pickup\u00A0&\u2009\u2009delivery\n"))
        assertEquals("José R", CensusHash.canonical("José\u00A0R"))
        assertEquals(CensusHash.canonical("a b"), CensusHash.canonical(CensusHash.canonical("a \t b")))
        assertEquals(CensusHash.of("Pickup & delivery"), CensusHash.of("Pickup\u00A0&\u00A0delivery"))
        assertEquals("", CensusHash.canonical(" \u00A0 "))
    }

    @Test
    fun `canonical is a fixed point (review OO1)`() {
        // The reproducer: a combining ring behind a zero-width joiner composes only if FORMAT is stripped
        // BEFORE NFKC; the old order needed a second pass.
        assertEquals("\u00C5dam", CensusHash.canonical("A\u200D\u030Adam"))
        val inputs = KindClassifierVectors.VECTORS.map { it.input } + listOf(
            "A\u200D\u030Adam", "Deli\u200Bver to Sam", "\uFF24eliver", " \u030Aa", "a\u2013b", "\uFB01nance",
        )
        inputs.forEach { x -> assertEquals(x, CensusHash.canonical(x), CensusHash.canonical(CensusHash.canonical(x))) }
        // Seeded property: canonical(canonical(x)) == canonical(x) over random strings from a hostile pool.
        val rnd = kotlin.random.Random(0x1160_0007L)
        val pool = "aAz \u00A0\u200B\u200C\u200D\uFEFF\u0301\u030A\u0327\u2010\u2212\uFF21\uFB01\u1E9E\u00DF\u03A3\u2028\t\u180E"
        repeat(3000) {
            val x = buildString { repeat(rnd.nextInt(0, 16)) { append(pool[rnd.nextInt(pool.length)]) } }
            val c = CensusHash.canonical(x)
            assertEquals(x, c, CensusHash.canonical(c))
            assertEquals(x, c, CensusHash.canonicalOrNull(x))
        }
    }

    @Test
    fun `ofCanonical hashes exactly the string it is given (review OO1)`() {
        assertEquals(CensusHash.of("  Accept "), CensusHash.ofCanonical("Accept"))
        assertEquals(CensusHash.of("A\u200D\u030Adam"), CensusHash.ofCanonical("\u00C5dam"))
        assertNotEquals(CensusHash.of(" Accept"), CensusHash.ofCanonical(" Accept"))
    }
}
