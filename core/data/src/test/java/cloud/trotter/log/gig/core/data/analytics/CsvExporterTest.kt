package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.domain.export.Csv
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.domain.analytics.ExpenseRecord
import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.domain.analytics.FuelRecord
import java.time.Instant
import cloud.trotter.log.gig.core.database.analytics.SessionRecordEntity
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation
import cloud.trotter.log.gig.domain.model.vehicle.DashVehicle
import cloud.trotter.log.gig.domain.model.vehicle.GasCar
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.model.vehicle.VehicleType
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
        milesToStore: Double? = null,
        milesToDropoff: Double? = null,
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
        milesToStore = milesToStore,
        milesToDropoff = milesToDropoff,
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
        val out = CsvExporter.export(
            listOf(delivery(1, "H-E-B", cashTip = 4.00, milesToStore = 1.20, milesToDropoff = 3.00)),
            emptyList(), utc, generatedAt,
        )
        val lines = out.deliveriesCsv.trim().lines()
        assertEquals(
            "date,time,platform,store,gross_pay,tip,base_pay,cash_tip,miles,miles_to_store," +
                "miles_to_dropoff,minutes,frozen_cost_per_mile,net_profit,pay_basis,cost_basis",
            lines[0],
        )
        assertEquals(
            "2026-07-05,14:03:27,DoorDash,H-E-B,8.50,3.25,5.25,4.00,4.20,1.20,3.00,12.50,0.165,7.81,DROP_SHARE,OFFER_FROZEN",
            lines[1],
        )
    }

    @Test fun deliveries_nullPerLegMileage_isEmptyField() {
        val out = CsvExporter.export(listOf(delivery(1, "H-E-B")), emptyList(), utc, generatedAt)
        val cells = out.deliveriesCsv.trim().lines()[1].split(",")
        // cash_tip is column index 7 (date,time,platform,store,gross_pay,tip,base_pay,cash_tip); the
        // per-leg columns are miles(8), miles_to_store(9), miles_to_dropoff(10) — null → empty.
        assertEquals("", cells[7])
        assertEquals("", cells[9])
        assertEquals("", cells[10])
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
            "start,end,platform,duration_minutes,reported_earnings,report_source,deliveries,offers_received," +
                "offers_accepted,offers_declined,offers_timeout,odometer_start,odometer_end,miles,vehicle_id,vehicle_label",
            lines[0],
        )
        val cols = lines[1].split(",")
        assertEquals("DoorDash", cols[2])
        assertEquals("DASH_SUMMARY", cols[5])
        assertEquals("60.00", cols[3])        // 1h duration
        assertEquals("42.00", cols[13])    // 1042 - 1000 miles
        assertEquals(listOf("", "Unknown vehicle"), cols.takeLast(2))
        assertEquals(
            "2026-07-05T13:03:27,2026-07-05T14:03:27,DoorDash,60.00,55.00,DASH_SUMMARY,3,5,3,1,1,1000.00,1042.00,42.00,,Unknown vehicle",
            lines[1],
        )
    }

    @Test fun sessionMiles_missingOdometer_isEmpty() {
        val out = CsvExporter.export(emptyList(), listOf(session(startOdo = null, lastOdo = null)), utc, generatedAt)
        assertEquals("", out.sessionsCsv.trim().lines()[1].split(",")[13])
    }

    @Test fun sessions_stampedVehicle_usesNameThenIdentityThenEveryEnglishType() {
        val named = Vehicle("named", "My car", 0, GasCar(year = "2020", make = "Toyota", model = "Prius"))
        val identity = Vehicle("identity", null, 0, GasCar(year = "2020", make = "Toyota", model = "Prius"))
        val partial = Vehicle("partial", null, 0, GasCar(year = "", make = "Toyota", model = " "))
        val types = VehicleType.entries.map { Vehicle(it.name, null, 0, it.defaultProfile()) }
        val vehicles = listOf(named, identity, partial) + types
        val out = CsvExporter.export(
            emptyList(), vehicles.map { session(id = it.id) }, utc, generatedAt,
            dashVehicles = vehicles.map { DashVehicle(it.id, it.id, 0, null) }, vehicles = vehicles,
        )
        assertEquals(
            listOf("My car", "2020 Toyota Prius", "Toyota", "Car", "Hybrid", "Electric car",
                "Motorcycle", "Scooter or moped", "E-bike", "Bicycle"),
            out.sessionsCsv.trim().lines().drop(1).map { it.split(",").last() },
        )
        assertEquals(vehicles.map { it.id }, out.sessionsCsv.trim().lines().drop(1).map { it.split(",")[14] })
    }

    @Test fun sessions_unknownRetiredAndMissingVehicles_keepHistoricalAttribution() {
        val retired = Vehicle("retired", "Old car", 0, retiredAt = 1)
        val out = CsvExporter.export(
            emptyList(), listOf("unstamped", "unknown", "retired", "missing").map { session(id = it) }, utc, generatedAt,
            dashVehicles = listOf(
                DashVehicle("unknown", null, 0, null), DashVehicle("retired", "retired", 0, null),
                DashVehicle("missing", "deleted-id", 0, null),
            ),
            vehicles = listOf(retired, Vehicle("active-today", "Today's car", 0)),
        )
        assertEquals(
            listOf(listOf("", "Unknown vehicle"), listOf("", "Unknown vehicle"),
                listOf("retired", "Old car"), listOf("deleted-id", "Unknown vehicle")),
            out.sessionsCsv.trim().lines().drop(1).map { it.split(",").takeLast(2) },
        )
    }

    @Test fun vehicleText_formulaLeadersAreNeutralizedInSessionsAndSummary() {
        val vehicle = Vehicle("=id", "=SUM(1,2)", 0)
        val out = CsvExporter.export(
            emptyList(), listOf(session()), utc, generatedAt,
            dashVehicles = listOf(DashVehicle("s1", vehicle.id, 0, null)), vehicles = listOf(vehicle),
        )
        assertTrue(out.sessionsCsv.contains(",'=id,\"'=SUM(1,2)\"\n"))
        assertTrue(out.summaryCsv.contains("vehicle_id,'=id\nvehicle_label,\"'=SUM(1,2)\"\n"))
    }

    @Test fun summary_vehicleMilesPerLocalTaxYear_sumToTotalAndSortUnknownLast() {
        val zone = ZoneId.of("America/Chicago")
        val yearEnd = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli() // Still 2025 locally.
        val rows = listOf(
            session("z25", yearEnd, startOdo = 0.0, lastOdo = 10.0),
            session("a25", millisIn(2025), startOdo = 0.0, lastOdo = 20.0),
            session("z25-again", millisIn(2025), startOdo = 10.0, lastOdo = 15.0),
            session("unknown25", millisIn(2025), startOdo = 0.0, lastOdo = 7.0),
            session("a26", millisIn(2026), startOdo = 0.0, lastOdo = 30.0),
            session("unknown26", millisIn(2026), startOdo = 0.0, lastOdo = 8.0),
            session("missing26", millisIn(2026), startOdo = 0.0, lastOdo = 9.0),
            session("no-miles", millisIn(2026), startOdo = null, lastOdo = null),
        )
        val stamps = listOf(
            DashVehicle("z25", "z", 0, null), DashVehicle("z25-again", "z", 0, null),
            DashVehicle("a25", "a", 0, null), DashVehicle("a26", "a", 0, null),
            DashVehicle("unknown26", null, 0, null), DashVehicle("missing26", "missing", 0, null),
            DashVehicle("no-miles", "unused", 0, null),
        )
        val vehicles = listOf(Vehicle("z", "Zebra", 0, retiredAt = 1), Vehicle("a", "Alpha", 0),
            Vehicle("unused", "Unused", 0))
        val summary = CsvExporter.export(emptyList(), rows, zone, generatedAt,
            dashVehicles = stamps, vehicles = vehicles).summaryCsv
        val blocks = summary.split("tax_year,").drop(1)
        assertEquals(2, blocks.size)
        for ((index, block) in blocks.withIndex()) {
            val lines = block.lines()
            val total = lines.single { it.startsWith("total_deductible_miles,") }.substringAfter(',').toDouble()
            val vehicleMiles = lines.filter { it.startsWith("vehicle_deductible_miles,") }.map { it.substringAfter(',').toDouble() }
            assertEquals(total, vehicleMiles.sum(), 0.0)
            assertEquals(if (index == 0) 42.0 else 47.0, total, 0.0)
            assertEquals(
                if (index == 0) listOf("Alpha", "Zebra", "Unknown vehicle")
                else listOf("Alpha", "Unknown vehicle", "Unknown vehicle"),
                lines.filter { it.startsWith("vehicle_label,") }.map { it.substringAfter(',') },
            )
        }
        assertTrue(summary.contains("vehicle_id,z\nvehicle_label,Zebra\nvehicle_deductible_miles,15.00\n"))
        assertTrue(summary.contains("vehicle_id,missing\nvehicle_label,Unknown vehicle\nvehicle_deductible_miles,9.00\n"))
        assertFalse(summary.contains("Unused"))
        assertFalse(summary.contains("vehicle_estimated_mileage_deduction"))
        assertTrue(summary.contains("estimated_mileage_deduction,29.40\nvehicle_id,a\n"))
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
            summary.contains("rate_note,gig.log doesn't have the 2027 IRS rate; this uses the 2026 rate."),
        )
        assertTrue(summary.contains(
            "rate_note,gig.log doesn't have the 2027 IRS rate; this uses the 2026 rate.\n" +
                "vehicle_id,\nvehicle_label,Unknown vehicle\nvehicle_deductible_miles,100.00\n",
        ))
        // Disclaimer starts with gig.log, so no cell in the summary begins with a formula leader.
        summary.trim().lines().forEach { row ->
            assertFalse("formula-leading cell leaked: $row", row.startsWith("=") || row.startsWith("@"))
        }
    }
    @Test fun sessions_and_summary_name_each_effective_source() {
        val sessions = listOf(
            session(id = "summary", reported = 30.0),
            session(id = "zero", reported = 0.0),
            session(id = "counter", reported = 20.0).copy(endSource = "early_offline"),
            // #1459: a stale running total (12) below its recorded pay (16) reports the recorded pay.
            session(id = "stale", reported = 12.0).copy(endSource = "early_offline"),
            session(id = "set", reported = 99.0).copy(reportOverrideMode = SessionReportOperation.SET, reportOverride = 25.0),
            session(id = "clear", reported = 99.0).copy(reportOverrideMode = SessionReportOperation.CLEAR),
            session(id = "none", reported = 0.0).copy(endSource = "early_offline"),
            session(id = "empty", reported = null),
        )
        val deliveries = listOf(
            delivery(1, "S", pay = 7.0, cashTip = 3.0).copy(sessionId = "clear"),
            delivery(2, "S", pay = 11.0).copy(sessionId = "none"),
            delivery(3, "S", pay = null).copy(sessionId = "empty"),
            delivery(4, "S", pay = 13.0).copy(sessionId = null),
            delivery(5, "S", pay = 25.0).copy(sessionId = "summary"),
            delivery(6, "S", pay = 10.0).copy(sessionId = "stale"),
            delivery(7, "S", pay = 6.0).copy(sessionId = "stale"),
        )
        val out = CsvExporter.export(deliveries, sessions, utc, generatedAt)
        val rows = out.sessionsCsv.trim().lines().drop(1).map { it.split(",") }
        assertEquals(listOf("DASH_SUMMARY", "DASH_SUMMARY", "IN_DASH_COUNTER", "RECORDED_PAY", "DRIVER_SET", "NONE", "NONE", "NONE"),
            rows.map { it[5] })
        assertEquals("16.00", rows[3][4])
        assertTrue(out.summaryCsv.contains("total_recorded_pay_earnings,16.00\n"))
        assertTrue(out.summaryCsv.contains("total_dash_summary_earnings,30.00\n"))
        assertTrue(out.summaryCsv.contains("total_in_dash_counter_earnings,20.00\n"))
        assertTrue(out.summaryCsv.contains("total_driver_set_earnings,25.00\n"))
        assertTrue(out.summaryCsv.contains("total_recorded_without_report,18.00\n"))
        assertFalse(out.summaryCsv.contains("total_reported_earnings,"))
    }

    @Test fun sessions_driverClearAndSet_exportEffectiveReportAndSummary() {
        val cleared = session(id = "483", reported = 40.14).copy(
            reportOverrideMode = SessionReportOperation.CLEAR, reportCorrectedAt = generatedAt,
        )
        val set = session(id = "484", reported = 11.75).copy(
            reportOverrideMode = SessionReportOperation.SET, reportOverride = 12.5, reportCorrectedAt = generatedAt,
        )
        val out = CsvExporter.export(emptyList(), listOf(cleared, set), utc, generatedAt)
        val rows = out.sessionsCsv.trim().lines()
        assertEquals("", rows[1].split(",")[4])
        assertEquals("12.50", rows[2].split(",")[4])
        assertEquals("NONE", rows[1].split(",")[5])
        assertEquals("DRIVER_SET", rows[2].split(",")[5])
        assertTrue(out.summaryCsv.contains("total_driver_set_earnings,12.50"))
        assertFalse(out.summaryCsv.contains("total_reported_earnings,"))
    }

    private val ledgerZone = ZoneId.of("America/Chicago")
    private val ledgerAt = Instant.parse("2026-10-19T04:59:00Z").toEpochMilli()
    private val expenses = listOf(
        ExpenseRecord(2, "dash-2", ledgerAt + 120_000, ExpenseCategory.SUPPLIES, 12.5, "Bags, \"large\"", null),
        ExpenseRecord(1, null, ledgerAt, ExpenseCategory.PARKING, 4.0, null, ledgerAt + 180_000),
    )
    private val fuel = listOf(FuelRecord(3, null, ledgerAt + 180_000, 30.0, null, 1234.5, null))

    private fun ledgerExport() = CsvExporter.export(
        emptyList(), emptyList(), ledgerZone, generatedAt, expenses, fuel,
    )

    @Test fun expenses_golden_preservesVoidsLabelsAttributionAndLocalDate() {
        assertEquals(
            "date,time,category,label,amount,dash_id,voided,vehicle_id,vehicle_label\n" +
                "2026-10-18,23:59:00,PARKING,,4.00,,true,,\n" +
                "2026-10-19,00:01:00,SUPPLIES,\"Bags, \"\"large\"\"\",12.50,dash-2,false,,\n",
            ledgerExport().expensesCsv,
        )
    }

    @Test fun fuel_golden_missingGallonsLeavesPriceEmpty() {
        assertEquals(
            "date,time,total,gallons,price_per_gallon,tracked_miles,dash_id,voided,vehicle_id,vehicle_label\n" +
                "2026-10-19,00:02:00,30.00,,,1234.50,,false,,\n",
            ledgerExport().fuelCsv,
        )
    }

    @Test fun ledgerText_neutralizesFormulaLeadersIncludingLabelsAndDashIds() {
        for (leader in listOf("=", "+", "-", "@", "\t", "\r")) {
            val out = CsvExporter.export(
                emptyList(), emptyList(), utc, generatedAt,
                // The category cell is an enum name, safe by construction; only text cells take the probe.
                listOf(expenses.first().copy(note = leader + "label", sessionId = leader + "dash")),
                listOf(fuel.first().copy(sessionId = leader + "dash")),
            )
            // CR triggers RFC quoting after the force-text prefix; other leaders stay unquoted.
            fun cell(value: String) = if (leader == "\r") "\"'$leader$value\"" else "'$leader$value"
            assertTrue(out.expensesCsv.contains(",${expenses.first().category.name},${cell("label")},12.50,${cell("dash")},false,,\n"))
            assertTrue(out.fuelCsv.endsWith(",${cell("dash")},false,,\n"))
        }
    }

    @Test fun fuel_sortedAndVoidedRowsRemainWithDerivedPrice() {
        val out = CsvExporter.export(
            emptyList(), emptyList(), ledgerZone, generatedAt,
            fuel = listOf(fuel.first(), fuel.first().copy(
                eventSequenceId = 4, sessionId = "old-dash", occurredAt = ledgerAt,
                totalAmount = 36.0, gallons = 10.0, odometerMiles = null, voidedAt = ledgerAt + 1,
            )),
        )
        assertEquals("2026-10-18,23:59:00,36.00,10.000,3.600,,old-dash,true,,", out.fuelCsv.lines()[1])
        assertTrue(out.summaryCsv.endsWith("expenses_total,0.00\nfuel_total,30.00\n"))
    }

    @Test fun ledgerSummary_appendsOnlyNonVoidedTotalsToExistingFigures() {
        val original = CsvExporter.export(listOf(delivery(1, "S")), listOf(session()), utc, generatedAt)
        val out = CsvExporter.export(listOf(delivery(1, "S")), listOf(session()), utc, generatedAt, expenses, fuel)
        assertEquals(original.deliveriesCsv, out.deliveriesCsv)
        assertEquals(original.sessionsCsv, out.sessionsCsv)
        assertEquals(
            original.summaryCsv.removeSuffix("expenses_total,0.00\nfuel_total,0.00\n") +
                "expenses_total,12.50\nfuel_total,30.00\n",
            out.summaryCsv,
        )
    }


    /** #1387 review r1: per-vehicle miles are rounded so they add up to the year's rounded total. */
    @Test fun `per vehicle miles add up to the year total after rounding`() {
        val shares = CsvExporter.apportionHundredths(listOf(1.004, 1.004), 2.008)
        assertEquals(201L, shares.sum())
        assertEquals(listOf(100L, 101L), shares.sorted())
        assertEquals(listOf(33L, 33L, 34L).sum(), CsvExporter.apportionHundredths(listOf(1.0 / 3, 1.0 / 3, 1.0 / 3), 1.0).sum())
        assertEquals(listOf(0L, 250L), CsvExporter.apportionHundredths(listOf(0.0, 2.5), 2.5))
        // Review r2: one vehicle at 1.005 mi prints 1.01 like the year total, not 1.00.
        assertEquals(listOf(101L), CsvExporter.apportionHundredths(listOf(1.005), 1.005))
        assertEquals(Csv.decimal(1.005), Csv.decimal(CsvExporter.apportionHundredths(listOf(1.005), 1.005)[0] / 100.0))
    }
    @Test fun receiptVehicles_knownRetiredUnknownAndNone() {
        val known = Vehicle("car", "Daily car", 0)
        val retired = Vehicle("old", "Old car", 1, retiredAt = 2)
        val ids = listOf(known.id, retired.id, "missing", null)
        val out = CsvExporter.export(emptyList(), emptyList(), utc, generatedAt,
            expenses = ids.mapIndexed { i, id -> expenses.first().copy(eventSequenceId = i.toLong(),
                occurredAt = ledgerAt + i, category = ExpenseCategory.CAR_CARE, vehicleId = id) },
            fuel = ids.mapIndexed { i, id -> fuel.first().copy(eventSequenceId = i.toLong(),
                occurredAt = ledgerAt + i, vehicleId = id) },
            vehicles = listOf(known, retired))
        val expected = listOf(
            listOf("car", "Daily car"), listOf("old", "Old car"),
            listOf("missing", CsvExporter.UNKNOWN_VEHICLE), listOf("", ""))
        for (csv in listOf(out.expensesCsv, out.fuelCsv)) {
            assertEquals(listOf("vehicle_id", "vehicle_label"), csv.lineSequence().first().split(',').takeLast(2))
            assertEquals(expected, csv.trimEnd('\n').lines().drop(1).map { it.split(',').takeLast(2) })
        }
    }

    @Test fun receiptVehicleText_neutralizesIdsAndLabels() {
        for (leader in listOf("=", "+", "-", "@", "\t", "\r")) {
            val vehicle = Vehicle(leader + "id", if (leader in listOf("\t", "\r")) null else leader + "SUM(1,2)", 0,
                GasCar(make = leader + "SUM(1,2)"))
            val out = CsvExporter.export(emptyList(), emptyList(), utc, generatedAt,
                expenses = listOf(expenses.first().copy(vehicleId = vehicle.id)),
                fuel = listOf(fuel.first().copy(vehicleId = vehicle.id)), vehicles = listOf(vehicle))
            val suffix = ",${Csv.textField(vehicle.id)},${Csv.textField(CsvExporter.vehicleLabel(vehicle))}\n"
            assertTrue(out.expensesCsv.endsWith(suffix))
            assertTrue(out.fuelCsv.endsWith(suffix))
        }
    }
}
