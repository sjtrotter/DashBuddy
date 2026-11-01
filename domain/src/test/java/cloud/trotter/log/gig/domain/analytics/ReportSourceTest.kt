package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportSourceTest {
    @Test
    fun `source machine override matrix follows the effective value owner`() {
        for (source in listOf(SessionEndSource.SUMMARY_SCREEN, SessionEndSource.EARLY_OFFLINE, null)) {
            for (machine in listOf(null, 0.0, 40.14, -5.0)) {
                // #1459: recorded pay; only 60.0 is above the 40.14 running total (40.14 is a tie).
                for (delivered in listOf(null, -5.0, 0.0, 20.0, 40.14, 60.0)) {
                    for ((mode, value) in listOf(
                        null to null,
                        SessionReportOperation.SET to 12.5,
                        SessionReportOperation.SET to 0.0,
                        SessionReportOperation.SET to null,
                        SessionReportOperation.CLEAR to null,
                    )) {
                        val expected = when {
                            mode == SessionReportOperation.CLEAR -> ReportSource.NONE
                            mode == SessionReportOperation.SET -> if (value == null) ReportSource.NONE else ReportSource.DRIVER_SET
                            machine == null -> ReportSource.NONE
                            source == SessionEndSource.SUMMARY_SCREEN -> ReportSource.DASH_SUMMARY
                            machine > 0.0 -> if (delivered == 60.0) ReportSource.RECORDED_PAY else ReportSource.IN_DASH_COUNTER
                            else -> ReportSource.NONE
                        }
                        val label = "source=$source machine=$machine delivered=$delivered mode=$mode value=$value"
                        val actual = ReportSource.of(machine, source, mode, value, delivered)
                        assertEquals(label, expected, actual)
                        assertEquals(label, SessionReportRule.effectiveReported(machine, source, mode, value, delivered) == null,
                            actual == ReportSource.NONE)
                    }
                }
            }
        }
    }

    @Test
    fun `a float tie of whole cents stays the running total (#1459)`() {
        val delivered = SessionReportRule.recordedPayOf(listOf(0.10, 0.20))
        assertEquals(ReportSource.IN_DASH_COUNTER, ReportSource.of(0.30, SessionEndSource.EARLY_OFFLINE, null, null, delivered))
        assertEquals(ReportSource.RECORDED_PAY, ReportSource.of(0.30, SessionEndSource.EARLY_OFFLINE, null, null, 0.31))
    }
}
