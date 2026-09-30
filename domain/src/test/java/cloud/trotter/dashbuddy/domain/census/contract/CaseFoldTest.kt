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
}
