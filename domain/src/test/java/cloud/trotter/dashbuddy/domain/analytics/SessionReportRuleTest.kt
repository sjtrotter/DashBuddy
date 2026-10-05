package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionReportOperation
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionReportRuleTest {
    @Test
    fun `effective report matrix preserves SET zero and clears positive machine values`() {
        for (source in listOf(SessionEndSource.SUMMARY_SCREEN, SessionEndSource.EARLY_OFFLINE, null)) {
            for (machine in listOf(null, 0.0, 40.14, -5.0)) { // -5.0 (Astra r1 P2): a negative non-summary value is no report
                val trusted = if (machine == 40.14 || source == SessionEndSource.SUMMARY_SCREEN) machine else null
                val cases = listOf(
                    Triple(null, null, trusted),
                    Triple(SessionReportOperation.SET, 12.5, 12.5),
                    Triple(SessionReportOperation.SET, 0.0, 0.0),
                    Triple(SessionReportOperation.CLEAR, null, null),
                )
                for ((mode, value, expected) in cases) {
                    assertEquals("source=$source machine=$machine mode=$mode value=$value", expected,
                        SessionReportRule.effectiveReported(machine, source, mode, value))
                }
            }
        }
    }
}
