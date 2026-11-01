package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShiftEarningsTest {
    @Test
    fun `live is the larger of the known values`() {
        assertNull(ShiftEarnings.live(null, null))
        assertEquals(12.5, ShiftEarnings.live(12.5, null)!!, 0.0)
        assertEquals(8.0, ShiftEarnings.live(null, 8.0)!!, 0.0)
        assertEquals(20.0, ShiftEarnings.live(12.5, 20.0)!!, 0.0) // recorded pay ahead of a lagging total
        assertEquals(30.0, ShiftEarnings.live(30.0, 20.0)!!, 0.0) // the app's total has overtaken it
        assertEquals(0.0, ShiftEarnings.live(0.0, null)!!, 0.0)
    }

    @Test
    fun `rested prefers the report and falls back to recorded pay`() {
        assertNull(ShiftEarnings.rested(null, null))
        assertEquals(40.0, ShiftEarnings.rested(40.0, null)!!, 0.0)
        assertEquals(18.0, ShiftEarnings.rested(null, 18.0)!!, 0.0)
        assertEquals(40.0, ShiftEarnings.rested(40.0, 18.0)!!, 0.0)
        assertEquals(15.0, ShiftEarnings.rested(15.0, 18.0)!!, 0.0) // the effective report wins even below
    }

    @Test
    fun `a summary zero and a SET are reports and a CLEAR falls back to recorded pay`() {
        fun rested(machine: Double?, source: String?, mode: String?, override: Double?, recorded: Double?) =
            ShiftEarnings.rested(SessionReportRule.effectiveReported(machine, source, mode, override, recorded), recorded)
        assertEquals(0.0, rested(0.0, SessionEndSource.SUMMARY_SCREEN, null, null, 9.0)!!, 0.0)
        // #1459: an early end's stale running total gives way to the higher recorded pay; a summary total does not.
        assertEquals(60.0, rested(40.0, SessionEndSource.EARLY_OFFLINE, null, null, 60.0)!!, 0.0)
        assertEquals(40.0, rested(40.0, SessionEndSource.SUMMARY_SCREEN, null, null, 60.0)!!, 0.0)
        assertEquals(9.0, rested(0.0, SessionEndSource.EARLY_OFFLINE, null, null, 9.0)!!, 0.0)
        assertEquals(25.0, rested(40.0, SessionEndSource.SUMMARY_SCREEN, SessionReportOperation.SET, 25.0, 9.0)!!, 0.0)
        assertEquals(9.0, rested(40.0, SessionEndSource.SUMMARY_SCREEN, SessionReportOperation.CLEAR, null, 9.0)!!, 0.0)
        assertNull(rested(40.0, SessionEndSource.SUMMARY_SCREEN, SessionReportOperation.CLEAR, null, null))
    }

    @Test
    fun `recorded pay is null unless a delivery is priced`() {
        assertNull(detail().recordedPay)
        assertNull(detail(null, null).recordedPay)
        assertEquals(0.0, detail(0.0).recordedPay!!, 0.0)
        assertEquals(7.5, detail(null, 7.5).recordedPay!!, 0.0)
        assertEquals(12.0, detail(7.5, 4.5, null).recordedPay!!, 0.0)
        assertEquals(0.0, detail(null, null).deliveredPay, 0.0) // the accounting read is unchanged
    }

    private fun detail(vararg pays: Double?) = SessionDetail(
        SessionRecord("s", Platform.DoorDash, 0, 1, null, reportedDurationMillis = null, miles = null,
            deliveries = pays.size, jobsCompleted = pays.size, offersReceived = 0, offersAccepted = 0,
            offersDeclined = 0, offersTimeout = 0),
        pays.mapIndexed { i, pay ->
            DeliveryRecord(i.toLong(), "s", Platform.DoorDash, "job-$i", "task-$i", null, i.toLong(), pay,
                PayBasis.OFFER_PAY, null, null, null, null, null)
        },
    )
}
