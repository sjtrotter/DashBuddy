package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayEarningsTest {
    private val start = 1_000_000L
    private val end = start + 86_400_000L

    private fun of(ended: List<EndedShiftPay> = emptyList(), live: List<LiveShiftPay> = emptyList()) =
        TodayEarnings.of(ended, live, start, end)

    @Test
    fun `no shift is zero`() {
        assertEquals(0.0, of(), 0.0)
    }

    @Test
    fun `a live shift with no figure adds nothing`() {
        assertEquals(0.0, of(live = listOf(LiveShiftPay("a", start + 1, null, null))), 0.0)
        assertEquals(12.0, of(live = listOf(LiveShiftPay("a", start + 1, null, 12.0))), 0.0)
    }

    @Test
    fun `two platforms add up and each live shift takes its larger figure`() {
        val live = listOf(
            LiveShiftPay("dd", start + 1, 30.0, 25.0),
            LiveShiftPay("uber", start + 2, null, 14.0),
        )
        assertEquals(44.0, of(live = live), 0.0)
    }

    @Test
    fun `a shift started yesterday and still live adds nothing`() {
        assertEquals(0.0, of(live = listOf(LiveShiftPay("y", start - 1, 50.0, 50.0))), 0.0)
        assertEquals(0.0, of(ended = listOf(EndedShiftPay("y", start - 1, 50.0, 50.0))), 0.0)
        assertEquals(0.0, of(ended = listOf(EndedShiftPay("t", end, 50.0, 50.0))), 0.0)
    }

    @Test
    fun `an ended shift counts its effective report and a SET wins`() {
        val set = SessionReportRule.effectiveReported(40.0, SessionEndSource.SUMMARY_SCREEN, SessionReportOperation.SET, 32.0)
        val cleared = SessionReportRule.effectiveReported(40.0, SessionEndSource.SUMMARY_SCREEN, SessionReportOperation.CLEAR, null)
        val ended = listOf(
            EndedShiftPay("set", start + 1, set, 20.0),
            EndedShiftPay("clear", start + 2, cleared, 9.0),
            EndedShiftPay("none", start + 3, null, null),
        )
        assertEquals(41.0, of(ended = ended), 0.0)
    }

    @Test
    fun `a session both live and ended counts once with its live figure`() {
        val ended = listOf(EndedShiftPay("a", start + 1, 40.0, 30.0))
        val live = listOf(LiveShiftPay("a", start + 1, 45.0, 30.0))
        assertEquals(45.0, of(ended, live), 0.0)
    }
}
