package cloud.trotter.log.gig.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMilesRuleTest {
    @Test
    fun `an override wins over the tracked span, zero included, even with missing anchors`() {
        for ((start, last) in listOf(100.0 to 159.4, null to 159.4, 100.0 to null, null to null)) {
            assertEquals(0.0, SessionMilesRule.effective(start, last, 0.0)!!, 0.0)
            assertEquals(3.5, SessionMilesRule.effective(start, last, 3.5)!!, 0.0)
        }
    }

    @Test
    fun `no override keeps the floored tracked span and null for a missing anchor`() {
        assertEquals(59.4, SessionMilesRule.effective(100.0, 159.4, null)!!, 1e-9)
        assertEquals(0.0, SessionMilesRule.effective(200.0, 150.0, null)!!, 0.0)
        assertNull(SessionMilesRule.effective(null, 159.4, null))
        assertNull(SessionMilesRule.effective(100.0, null, null))
        assertNull(SessionMilesRule.effective(null, null, null))
        assertEquals(SessionMilesRule.machine(100.0, 159.4), SessionMilesRule.effective(100.0, 159.4, null))
    }

    @Test
    fun `a SET value is finite and between 0 and 2 000`() {
        for (ok in listOf(0.0, 0.1, 59.4, 2_000.0)) assertTrue("$ok", SessionMilesRule.isValidSet(ok))
        for (bad in listOf(null, -0.1, 2_000.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse("$bad", SessionMilesRule.isValidSet(bad))
        }
    }
}
