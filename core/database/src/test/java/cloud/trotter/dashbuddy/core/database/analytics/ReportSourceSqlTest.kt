package cloud.trotter.dashbuddy.core.database.analytics

import cloud.trotter.dashbuddy.domain.analytics.PayBasis
import cloud.trotter.dashbuddy.domain.analytics.ReportSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReportSourceSqlTest {
    @Test
    fun `SQL source literals are exactly the domain wires`() {
        val literals = Regex("(?:THEN|ELSE) '([^']+)'")
            .findAll(SessionReportSql.REPORT_SOURCE_SQL).map { it.groupValues[1] }.toSet()
        assertEquals(ReportSource.values().map { it.wire }.toSet(), literals)
    }

    @Test
    fun `offer estimates interpolate the domain wire in both DAO aggregates`() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "core/database/src/main").isDirectory }
        val source = File(root, "core/database/src/main/java/cloud/trotter/dashbuddy/core/database/analytics/AnalyticsDao.kt").readText()
        val mix = source.substringBefore("fun payMixTotals").substringAfterLast("@Query(")
        val predicate = "payBasis = '\${PayBasis.OFFER_PAY}'"
        assertEquals("OFFER_PAY", PayBasis.OFFER_PAY)
        assertEquals(2, mix.windowed(predicate.length).count { it == predicate })
        assertTrue(mix.contains("AS offerEstimatePay"))
        assertTrue(mix.contains("AS offerEstimateDeliveries"))
    }
}
