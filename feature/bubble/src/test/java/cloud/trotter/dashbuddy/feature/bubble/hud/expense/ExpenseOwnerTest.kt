package cloud.trotter.dashbuddy.feature.bubble.hud.expense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenseOwnerTest {
    @Test fun `active dash owns the entry`() {
        assertEquals("live", owner("live"))
    }

    @Test fun `no active dash leaves the entry unassigned`() {
        assertNull(owner(null))
    }
}
