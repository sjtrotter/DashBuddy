package cloud.trotter.log.gig.domain.analytics.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreQueryTest {

    private fun query(text: String) = StoreQuery.of(text)!!

    @Test fun `case and extra spaces are ignored`() {
        assertTrue(query(" wing   STOP ").matches("Wing Stop"))
        assertTrue(query("WINGSTOP").matches("Wingstop"))
        assertTrue(query("wing stop").matches("Wing   Stop"))
    }

    @Test fun `collapsing never joins words`() {
        assertFalse(query("wing stop").matches("Wingstop"))
        assertFalse(query("wingstop").matches("Wing Stop"))
    }

    @Test fun `a stack's second store is found`() {
        assertTrue(query("chipotle").matches("Five Guys & Chipotle"))
        assertTrue(query("five guys").matches("Five Guys & Chipotle"))
    }

    @Test fun `a blank query is no query, and a missing name never matches`() {
        assertNull(StoreQuery.of(""))
        assertNull(StoreQuery.of(" \t\n "))
        assertFalse(query("a").matches(null))
        assertFalse(query("a").matches("   "))
    }

    @Test fun `normalize lowercases, collapses and trims`() {
        assertEquals("five guys & chipotle", StoreQuery.normalize("  Five\tGuys  &\nChipotle "))
    }
}
