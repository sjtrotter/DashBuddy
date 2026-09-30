package cloud.trotter.dashbuddy.domain.census.contract

import org.junit.Assert.assertEquals
import org.junit.Test

/** #1160 review HH1 — the one case fold. */
class CaseFoldTest {

    @Test
    fun `fold equates the forms a plain lowercase does not`() {
        assertEquals(CaseFold.fold("ΝΙΚΟΣ"), CaseFold.fold("νικος"))
        assertEquals(CaseFold.fold("νικοσ"), CaseFold.fold("νικος"))
        assertEquals(CaseFold.fold("Groß"), CaseFold.fold("GROSS"))
        assertEquals(CaseFold.fold("Adam"), CaseFold.fold("ADAM"))
        assertEquals("gross", CaseFold.fold("Groß"))
    }

    @Test
    fun `capital sharp S folds with the small one, and the fold is idempotent (review MM2)`() {
        assertEquals(CaseFold.fold("Groß"), CaseFold.fold("GRO\u1E9E"))
        assertEquals("gross", CaseFold.fold("GRO\u1E9E"))
        listOf("Groß", "GRO\u1E9E", "GROSS", "ΝΙΚΟΣ", "νικος", "McKenna").forEach {
            assertEquals(it, CaseFold.fold(it), CaseFold.fold(CaseFold.fold(it)))
        }
    }
}
