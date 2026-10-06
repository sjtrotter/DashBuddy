package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionReportOperation
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportSourceTest {
    @Test
    fun `source machine override matrix follows the effective value owner`() {
        for (source in listOf(SessionEndSource.SUMMARY_SCREEN, SessionEndSource.EARLY_OFFLINE, null)) {
            for (machine in listOf(null, 0.0, 40.14, -5.0)) {
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
                        machine > 0.0 -> ReportSource.IN_DASH_COUNTER
                        else -> ReportSource.NONE
                    }
                    val label = "source=$source machine=$machine mode=$mode value=$value"
                    val actual = ReportSource.of(machine, source, mode, value)
                    assertEquals(label, expected, actual)
                    assertEquals(label, SessionReportRule.effectiveReported(machine, source, mode, value) == null,
                        actual == ReportSource.NONE)
                }
            }
        }
    }
}
