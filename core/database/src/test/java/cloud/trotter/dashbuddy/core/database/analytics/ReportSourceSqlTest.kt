package cloud.trotter.dashbuddy.core.database.analytics

import cloud.trotter.dashbuddy.domain.analytics.PayBasis
import cloud.trotter.dashbuddy.domain.analytics.ReportSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSourceSqlTest {
    @Test
    fun `SQL source literals are exactly the domain wires`() {
        val literals = Regex("(?:THEN|ELSE) '([^']+)'")
            .findAll(SessionReportSql.REPORT_SOURCE_SQL).map { it.groupValues[1] }.toSet()
        assertEquals(ReportSource.values().map { it.wire }.toSet(), literals)
    }

}
