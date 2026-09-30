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
}
