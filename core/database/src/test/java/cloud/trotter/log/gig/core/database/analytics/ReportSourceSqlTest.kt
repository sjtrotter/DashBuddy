package cloud.trotter.log.gig.core.database.analytics

import cloud.trotter.log.gig.domain.analytics.ANALYTICS_MONEY_EPSILON
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.ReportSource
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

    @Test
    fun `the recorded pay tie margin is the analytics money epsilon and both mirrors use it (#1459)`() {
        assertEquals("d.deliveredPay > s.reportedEarnings + $ANALYTICS_MONEY_EPSILON", SessionReportSql.RECORDED_PAY_ABOVE_SQL)
        assertTrue(SessionReportSql.EFFECTIVE_REPORTED_SQL.contains(SessionReportSql.RECORDED_PAY_ABOVE_SQL))
        assertTrue(SessionReportSql.REPORT_SOURCE_SQL.contains("WHEN ${SessionReportSql.RECORDED_PAY_ABOVE_SQL} THEN 'RECORDED_PAY'"))
    }

}
