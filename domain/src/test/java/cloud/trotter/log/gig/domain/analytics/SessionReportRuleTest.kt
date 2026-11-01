package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReportRuleTest {
    @Test
    fun `effective report matrix preserves SET zero and clears positive machine values`() {
        for (source in listOf(SessionEndSource.SUMMARY_SCREEN, SessionEndSource.EARLY_OFFLINE, null)) {
            for (machine in listOf(null, 0.0, 40.14, -5.0)) { // -5.0 (Astra r1 P2): a negative non-summary value is no report
                // #1459: -5.0 and 40.14 (an exact tie) never lift the running total; 60.0 does.
                for (delivered in listOf(null, -5.0, 0.0, 20.0, 40.14, 60.0)) {
                    val trusted = when {
                        source == SessionEndSource.SUMMARY_SCREEN -> machine
                        machine == 40.14 -> if (delivered == 60.0) 60.0 else 40.14
                        else -> null
                    }
                    val cases = listOf(
                        Triple(null, null, trusted),
                        Triple(SessionReportOperation.SET, 12.5, 12.5),
                        Triple(SessionReportOperation.SET, 0.0, 0.0),
                        Triple(SessionReportOperation.CLEAR, null, null),
                    )
                    for ((mode, value, expected) in cases) {
                        assertEquals("source=$source machine=$machine delivered=$delivered mode=$mode value=$value", expected,
                            SessionReportRule.effectiveReported(machine, source, mode, value, delivered))
                    }
                }
            }
        }
    }

    @Test
    fun `an early end floors a stale running total at recorded pay and a summary total stays authoritative (#1459)`() {
        assertEquals(16.0, SessionReportRule.effectiveReported(12.0, SessionEndSource.EARLY_OFFLINE, null, null, 16.0)!!, 0.0)
        assertEquals(16.0, SessionReportRule.effectiveReported(12.0, null, null, null, 16.0)!!, 0.0)
        assertEquals(12.0, SessionReportRule.effectiveReported(12.0, SessionEndSource.SUMMARY_SCREEN, null, null, 16.0)!!, 0.0)
        assertNull("D1: no running total stays no report",
            SessionReportRule.effectiveReported(null, SessionEndSource.EARLY_OFFLINE, null, null, 16.0))
        assertNull(SessionReportRule.effectiveReported(12.0, SessionEndSource.EARLY_OFFLINE, SessionReportOperation.CLEAR, null, 16.0))
        assertEquals(9.0, SessionReportRule.effectiveReported(12.0, SessionEndSource.EARLY_OFFLINE, SessionReportOperation.SET, 9.0, 16.0)!!, 0.0)
    }

    @Test
    fun `a float tie of whole cents is not above the running total (#1459)`() {
        val delivered = SessionReportRule.recordedPayOf(listOf(0.10, 0.20))!!
        assertTrue("the binary sum is above 0.30", delivered > 0.30)
        assertFalse(SessionReportRule.recordedPayAbove(0.30, delivered))
        assertEquals(0.30, SessionReportRule.effectiveReported(0.30, SessionEndSource.EARLY_OFFLINE, null, null, delivered)!!, 0.0)
        assertTrue(SessionReportRule.recordedPayAbove(0.30, 0.31))
        assertFalse(SessionReportRule.recordedPayAbove(0.30, null))
    }

    @Test
    fun `recorded pay sums priced rows and is null when none is priced (#1459)`() {
        assertNull(SessionReportRule.recordedPayOf(emptyList()))
        assertNull(SessionReportRule.recordedPayOf(listOf(null, null)))
        assertEquals(0.0, SessionReportRule.recordedPayOf(listOf(null, 0.0))!!, 0.0)
        assertEquals(16.0, SessionReportRule.recordedPayOf(listOf(10.0, null, 6.0))!!, 0.0)
    }
}
