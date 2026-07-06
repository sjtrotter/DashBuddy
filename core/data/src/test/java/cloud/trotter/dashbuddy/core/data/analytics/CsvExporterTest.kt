package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Entity → CSV assembly, hash exclusion, header shape, and the summary deduction (#319). */
class CsvExporterTest {

    private val utc = ZoneId.of("UTC")
    private val generatedAt = 1_783_260_207_000L // 2026-07-05T14:03:27Z

    /** Noon-UTC on a fixed day of [year] — pins a session into a specific local tax year. */
    private fun millisIn(year: Int): Long =
        ZonedDateTime.of(year, 6, 15, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    private fun delivery(
        seq: Long,
        store: String?,
        completedAt: Long = generatedAt,
        pay: Double? = 8.50,
        basis: String = "DROP_SHARE",
        cashTip: Double? = null,
    ) = DeliveryRecordEntity(
        eventSequenceId = seq,
        sessionId = "s1",
        platform = "doordash",
        jobId = "j$seq",
        taskId = "t$seq",
        storeName = store,
        customerHash = "CUSTOMER_HASH_SHOULD_NOT_APPEAR",
        addressHash = "ADDRESS_HASH_SHOULD_NOT_APPEAR",
        phaseStartedAt = completedAt - 600_000,
        arrivedAt = completedAt - 300_000,
        completedAt = completedAt,
        deadlineMillis = null,
        realizedPay = pay,
        payBasis = basis,
        tip = 3.25,
        basePay = 5.25,
        odometerAtCompletion = 1000.0,
        realizedMiles = 4.2,
        realizedMinutes = 12.5,
        frozenCostPerMile = 0.165,
        netProfit = 7.81,
        costBasis = "OFFER_FROZEN",
        cashTip = cashTip,
    )

    private fun session(
        id: String = "s1",
        startedAt: Long = generatedAt - 3_600_000,
        endedAt: Long? = generatedAt,
        startOdo: Double? = 1000.0,
        lastOdo: Double? = 1042.0,
        reported: Double? = 55.00,
    ) = SessionRecordEntity(
        sessionId = id,
        platform = "doordash",
        startedAt = startedAt,
        endedAt = endedAt,
        lastEventAt = endedAt ?: startedAt,
        endSource = "summary_screen",
        startOdometer = startOdo,
        lastOdometer = lastOdo,
        reportedEarnings = reported,
        reportedDurationMillis = 3_600_000,
        offersReceived = 5,
        offersAccepted = 3,
        offersDeclined = 1,
        offersTimeout = 1,
        deliveries = 3,
        jobsCompleted = 3,
    )

    @Test fun deliveries_headerAndRow_withPlatformDisplayName() {
        val out = CsvExporter.export(listOf(delivery(1, "H-E-B", cashTip = 4.00)), emptyList(), utc, generatedAt)
        val lines = out.deliveriesCsv.trim().lines()
        assertEquals(
            "date,time,platform,store,gross_pay,tip,base_pay,cash_tip,miles,minutes,frozen_cost_per_mile,net_profit,pay_basis,cost_basis",
            lines[0],
        )
        assertEquals(
            "2026-07-05,14:03:27,DoorDash,H-E-B,8.50,3.25,5.25,4.00,4.20,12.50,0.165,7.81,DROP_SHARE,OFFER_FROZEN",
            lines[1],
        )
    }

    @Test fun deliveries_nullCashTip_isEmptyField() {
        val out = CsvExporter.export(listOf(delivery(1, "H-E-B")), emptyList(), utc, generatedAt)
        // cash_tip is column index 7 (date,time,platform,store,gross_pay,tip,base_pay,cash_tip).
        assertEquals("", out.deliveriesCsv.trim().lines()[1].split(",")[7])
    }

    @Test fun summary_totalCashTips_line() {
        val out = CsvExporter.export(
            listOf(delivery(1, "S", cashTip = 4.00), delivery(2, "S", cashTip = 1.50)),
            listOf(session()), utc, generatedAt,
        )
        assertTrue(out.summaryCsv.contains("total_cash_tips,5.50"))
    }

    @Test fun deliveries_offerPayBasis_appearsVerbatim() {
        // #691: an OFFER_PAY estimate row exports its pay_basis verbatim so the basis column IS the
        // never-silent disclosure for the tax-preparer artifact (no separate UI qualifier needed).
        val out = CsvExporter.export(listOf(delivery(1, "H-E-B", basis = "OFFER_PAY")), emptyList(), utc, generatedAt)
        val row = out.deliveriesCsv.trim().lines()[1]
        assertTrue("OFFER_PAY basis missing in: $row", row.endsWith(",OFFER_PAY,OFFER_FROZEN"))
    }

    @Test fun deliveries_storeWithComma_isQuoted() {
        val out = CsvExporter.export(listOf(delivery(1, "Chili's, Cedar Park")), emptyList(), utc, generatedAt)
        assertTrue(out.deliveriesCsv.contains("\"Chili's, Cedar Park\""))
    }

    @Test fun formulaInjection_storeName_isNeutralizedEndToEnd() {
        // Store names are third-party UI (untrusted). A formula payload must land in the written
        // file carrying the force-text prefix so a spreadsheet renders it as literal text.
        val out = CsvExporter.export(
            listOf(delivery(1, "=cmd(\"/c calc\")!A1")),
            emptyList(), utc, generatedAt,
        )
        val row = out.deliveriesCsv.trim().lines()[1]
        // The neutralized cell: '-prefixed, and RFC-4180 quoted (payload contains quotes).
        assertTrue("neutralized cell missing in: $row", row.contains("\"'=cmd("))
        // No cell in the row may still BEGIN with the raw formula (quoted or bare).
        assertFalse("raw formula cell leaked in: $row", row.contains(",=cmd") || row.startsWith("=cmd"))
        assertFalse("raw quoted formula cell leaked in: $row", row.contains(",\"=cmd"))
        // Program-generated negatives stay bare and machine-parseable (strict-numeric exemption).
        val negOut = CsvExporter.export(listOf(delivery(2, "S", pay = -2.50)), emptyList(), utc, generatedAt)
        assertEquals("-2.50", negOut.deliveriesCsv.trim().lines()[1].split(",")[4])
    }

    @Test fun hashes_areNeverExported() {
        val out = CsvExporter.export(
            listOf(delivery(1, "H-E-B")),
            listOf(session()),
            utc,
            generatedAt,
        )
        val all = out.deliveriesCsv + out.sessionsCsv + out.summaryCsv
        assertFalse(all.contains("CUSTOMER_HASH_SHOULD_NOT_APPEAR"))
        assertFalse(all.contains("ADDRESS_HASH_SHOULD_NOT_APPEAR"))
    }

    @Test fun deliveries_sortedByCompletedAt() {
        val out = CsvExporter.export(
            listOf(
                delivery(2, "Late", completedAt = generatedAt + 1000),
                delivery(1, "Early", completedAt = generatedAt),
            ),
            emptyList(), utc, generatedAt,
        )
        val lines = out.deliveriesCsv.trim().lines()
        assertTrue(lines[1].contains("Early"))
        assertTrue(lines[2].contains("Late"))
    }

    @Test fun nullPay_becomesEmptyField_notZero() {
        val out = CsvExporter.export(listOf(delivery(1, "S", pay = null)), emptyList(), utc, generatedAt)
        val row = out.deliveriesCsv.trim().lines()[1]
        // gross_pay is the 5th column (index 4) and must be empty, not "0.00".
        assertEquals("", row.split(",")[4])
    }

    @Test fun sessions_headerAndDerivedMiles() {
        val out = CsvExporter.export(emptyList(), listOf(session()), utc, generatedAt)
        val lines = out.sessionsCsv.trim().lines()
        assertEquals(
            "start,end,platform,duration_minutes,reported_earnings,deliveries,offers_received," +
                "offers_accepted,offers_declined,offers_timeout,odometer_start,odometer_end,miles",
            lines[0],
        )
        val cols = lines[1].split(",")
        assertEquals("DoorDash", cols[2])
        assertEquals("60.00", cols[3])        // 1h duration
        assertEquals("42.00", cols.last())    // 1042 - 1000 miles
    }

    @Test fun sessionMiles_missingOdometer_isEmpty() {
        val out = CsvExporter.export(emptyList(), listOf(session(startOdo = null, lastOdo = null)), utc, generatedAt)
        assertEquals("", out.sessionsCsv.trim().lines()[1].split(",").last())
    }

    @Test fun summary_deductionMath() {
        // Default session starts 2026-07-05 (an hour before generatedAt) → 2026 rate ($0.725/mi).
        val out = CsvExporter.export(
            listOf(delivery(1, "S")),
            listOf(session(startOdo = 1000.0, lastOdo = 1100.0)), // 100 mi
            utc, generatedAt,
        )
        val summary = out.summaryCsv
        assertTrue(summary.contains("tax_year,2026"))
        assertTrue(summary.contains("irs_business_rate_per_mile,0.725"))
        assertTrue(summary.contains("total_deductible_miles,100.00"))
        assertTrue(summary.contains("estimated_mileage_deduction,72.50")) // 100 * 0.725
        assertTrue(summary.contains("total_sessions,1"))
        assertTrue(summary.contains("total_deliveries,1"))
    }

    @Test fun summary_yearBoundarySpan_emitsOneGroupPerYear_withEachYearsRate() {
        val out = CsvExporter.export(
            emptyList(),
            listOf(
                session(id = "a", startedAt = millisIn(2025), endedAt = millisIn(2025), startOdo = 1000.0, lastOdo = 1100.0), // 100 mi @2025
                session(id = "b", startedAt = millisIn(2026), endedAt = millisIn(2026), startOdo = 2000.0, lastOdo = 2200.0), // 200 mi @2026
            ),
            utc, generatedAt,
        )
        val summary = out.summaryCsv
        // Two tax-year groups, each with its own rate + deduction.
        assertTrue(summary.contains("tax_year,2025"))
        assertTrue(summary.contains("tax_year,2026"))
        assertTrue(summary.contains("irs_business_rate_per_mile,0.700"))
        assertTrue(summary.contains("irs_business_rate_per_mile,0.725"))
        assertTrue(summary.contains("estimated_mileage_deduction,70.00"))  // 100 * 0.70
        assertTrue(summary.contains("estimated_mileage_deduction,145.00")) // 200 * 0.725
        // Ascending order: the 2025 group precedes the 2026 group.
        assertTrue(summary.indexOf("tax_year,2025") < summary.indexOf("tax_year,2026"))
        // No stray disclaimer for years with a published rate.
        assertFalse(summary.contains("rate_note"))
    }

    @Test fun summary_unknownYear_usesLatestRate_withFormulaSafeDisclaimer() {
        val out = CsvExporter.export(
            emptyList(),
            listOf(session(startedAt = millisIn(2027), endedAt = millisIn(2027), startOdo = 1000.0, lastOdo = 1100.0)), // 100 mi @2027 (unpublished)
            utc, generatedAt,
        )
        val summary = out.summaryCsv
        assertTrue(summary.contains("tax_year,2027"))
        // Falls back to the latest known rate (2026 = $0.725/mi) → 100 * 0.725 = 72.50.
        assertTrue(summary.contains("irs_business_rate_per_mile,0.725"))
        assertTrue(summary.contains("estimated_mileage_deduction,72.50"))
        // Explicit, clearly-labelled disclaimer line (routed through Csv.textField — formula-safe).
        assertTrue(
            summary.contains("rate_note,2027 rate not yet published — estimated at the 2026 rate"),
        )
        // Disclaimer starts with a digit, so no cell in the summary begins with a formula leader.
        summary.trim().lines().forEach { row ->
            assertFalse("formula-leading cell leaked: $row", row.startsWith("=") || row.startsWith("@"))
        }
    }
}
