package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Locks the customer display SSOT (#503 / the premature "Delivery for Customer" card). A recipient
 * whose name hash hasn't resolved must render as a clearly-generic label, never the proper-noun
 * "Customer" that reads like a specific person's name; a resolved recipient shows the 6-char
 * privacy-hash prefix.
 */
class DisplayNamesTest {

    @Test
    fun `a resolved recipient shows the 6-char privacy-hash prefix`() {
        assertEquals("680f4a", customerDisplayName("680f4a1b2c3d4e5f"))
    }

    @Test
    fun `an unresolved recipient shows a generic label, not the proper-noun Customer`() {
        assertEquals("the customer", customerDisplayName(null))
        assertNotEquals(
            "an unresolved recipient must not read as a specific name",
            "Customer",
            customerDisplayName(null),
        )
    }

    // #568 — the dropoff display label is store-flavored, never the raw hash.

    @Test
    fun `customerLabel is store-flavored, never the raw hash (#568)`() {
        assertEquals("H-E-B's customer", customerLabel("H-E-B"))
        assertEquals("Maple Street's customer", customerLabel("Maple Street"))
    }

    @Test
    fun `customerLabel falls back to the generic when the store is unknown or blank (#568)`() {
        assertEquals("the customer", customerLabel(null))
        assertEquals("the customer", customerLabel(""))
        assertEquals("the customer", customerLabel("   "))
        assertEquals("the customer", customerLabel(UNKNOWN_STORE))
    }
}
