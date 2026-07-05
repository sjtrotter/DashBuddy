package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.domain.export.Csv
import cloud.trotter.dashbuddy.domain.export.IrsMileage
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.ZoneId

/**
 * Formats the durable analytics read-model into the free-tier CSV export (#319) — three files a
 * driver can hand to a spreadsheet or a tax preparer. Pure: entity lists in, CSV strings out. No
 * Android, no IO, no network — the SAF write edge lives in `:app`. Unit-tested directly.
 *
 * **Row-level RAW export, bucketing-free.** Unlike the period read-model (session-anchored buckets,
 * #655), this ships the underlying rows as-is: a delivery row keys off its own `completedAt`, a
 * session row off its own `startedAt`. There is no period math to disagree with — it's the driver's
 * own records, dumped. Callers filter by a `completedAt` / `startedAt` range (v1 exports all-time).
 *
 * **Privacy posture (Pledges / Principle 6).** Store names are *merchant* names — driver-owned,
 * exportable. `customerHash`/`addressHash` are deliberately **excluded**: they are edge-hashed
 * customer PII with no purpose in a tax/earnings spreadsheet, so the privacy-lean default is to not
 * ship them at all. No customer name/address ever existed in these rows (hashed at the edge), and
 * even the hashes stay home. No network path; SAF needs no permission.
 *
 * **Untrusted-text posture:** store names (and the `?: wire` platform fallback) originate in
 * third-party UI, so every text-typed cell routes through [Csv.textField], which neutralizes
 * spreadsheet formula injection (a leading `=`/`+`/`-`/`@`/TAB/CR gets a `'` force-text prefix)
 * on top of RFC-4180 quoting. Numeric/timestamp cells come from the [Csv] numeric emitters —
 * program-generated `[-0-9.T:]` strings, safe by construction, left bare so `-2.50` stays
 * machine-parseable.
 *
 * Platform is registry-resolved to its display name via [Platform.fromWire] — never a raw wire
 * literal compared in logic (Principle 8).
 */
object CsvExporter {

    /** The three CSV documents written side-by-side into the chosen directory. */
    data class Bundle(
        val deliveriesCsv: String,
        val sessionsCsv: String,
        val summaryCsv: String,
    )

    const val DELIVERIES_FILENAME = "deliveries.csv"
    const val SESSIONS_FILENAME = "sessions.csv"
    const val SUMMARY_FILENAME = "summary.csv"

    private val DELIVERY_HEADER = listOf(
        "date", "time", "platform", "store", "gross_pay", "tip", "base_pay",
        "miles", "minutes", "frozen_cost_per_mile", "net_profit", "pay_basis", "cost_basis",
    )

    private val SESSION_HEADER = listOf(
        "start", "end", "platform", "duration_minutes", "reported_earnings", "deliveries",
        "offers_received", "offers_accepted", "offers_declined", "offers_timeout",
        "odometer_start", "odometer_end", "miles",
    )

    /**
     * Build all three CSVs. [deliveries]/[sessions] are the raw rows (any order; sorted here for a
     * stable, chronological export). [zone] is the device's local zone (passed in so this stays
     * pure/testable); [generatedAtMillis] stamps the summary's export time.
     */
    fun export(
        deliveries: List<DeliveryRecordEntity>,
        sessions: List<SessionRecordEntity>,
        zone: ZoneId,
        generatedAtMillis: Long,
    ): Bundle = Bundle(
        deliveriesCsv = deliveriesCsv(deliveries.sortedBy { it.completedAt }, zone),
        sessionsCsv = sessionsCsv(sessions.sortedBy { it.startedAt }, zone),
        summaryCsv = summaryCsv(deliveries, sessions, zone, generatedAtMillis),
    )

    private fun platformName(wire: String): String = Platform.fromWire(wire)?.displayName ?: wire

    private fun sessionMiles(s: SessionRecordEntity): Double? {
        val start = s.startOdometer ?: return null
        val last = s.lastOdometer ?: return null
        return (last - start).coerceAtLeast(0.0)
    }

    private fun deliveriesCsv(rows: List<DeliveryRecordEntity>, zone: ZoneId): String {
        val sb = StringBuilder()
        sb.append(Csv.row(DELIVERY_HEADER.map { Csv.textField(it) })).append('\n')
        for (r in rows) {
            sb.append(
                Csv.row(
                    listOf(
                        Csv.isoDate(r.completedAt, zone),
                        Csv.isoTime(r.completedAt, zone),
                        Csv.textField(platformName(r.platform)),
                        Csv.textField(r.storeName),
                        Csv.money(r.realizedPay),
                        Csv.money(r.tip),
                        Csv.money(r.basePay),
                        Csv.decimal(r.realizedMiles),
                        Csv.decimal(r.realizedMinutes),
                        Csv.money(r.frozenCostPerMile, digits = 3),
                        Csv.money(r.netProfit),
                        Csv.textField(r.payBasis),
                        Csv.textField(r.costBasis),
                    )
                )
            ).append('\n')
        }
        return sb.toString()
    }

    private fun sessionsCsv(rows: List<SessionRecordEntity>, zone: ZoneId): String {
        val sb = StringBuilder()
        sb.append(Csv.row(SESSION_HEADER.map { Csv.textField(it) })).append('\n')
        for (s in rows) {
            // Online duration: to the close if known, else the last event seen (still-live / orphaned).
            val durationMillis = (s.endedAt ?: s.lastEventAt) - s.startedAt
            sb.append(
                Csv.row(
                    listOf(
                        Csv.isoDateTime(s.startedAt, zone),
                        Csv.isoDateTime(s.endedAt, zone),
                        Csv.textField(platformName(s.platform)),
                        Csv.millisToMinutes(durationMillis),
                        Csv.money(s.reportedEarnings),
                        Csv.int(s.deliveries),
                        Csv.int(s.offersReceived),
                        Csv.int(s.offersAccepted),
                        Csv.int(s.offersDeclined),
                        Csv.int(s.offersTimeout),
                        Csv.decimal(s.startOdometer),
                        Csv.decimal(s.lastOdometer),
                        Csv.decimal(sessionMiles(s)),
                    )
                )
            ).append('\n')
        }
        return sb.toString()
    }

    /**
     * A `metric,value` summary — the tax-preparer line the issue calls for: total odometer-derived
     * business miles × the IRS standard business rate, with the tax year and rate stated explicitly
     * so the deduction number is never ambiguous about which year produced it.
     */
    private fun summaryCsv(
        deliveries: List<DeliveryRecordEntity>,
        sessions: List<SessionRecordEntity>,
        zone: ZoneId,
        generatedAtMillis: Long,
    ): String {
        val totalMiles = sessions.sumOf { sessionMiles(it) ?: 0.0 }
        val deduction = IrsMileage.deduction(totalMiles)
        val totalReported = sessions.mapNotNull { it.reportedEarnings }.sum()
        val totalRealized = deliveries.mapNotNull { it.realizedPay }.sum()

        val sb = StringBuilder()
        sb.append(Csv.row(listOf(Csv.textField("metric"), Csv.textField("value")))).append('\n')
        // Metric names are program constants; values below are pre-encoded numeric/timestamp
        // cells except date_range's literal, which goes through textField like any text cell.
        fun line(metric: String, encodedValue: String) {
            sb.append(Csv.row(listOf(Csv.textField(metric), encodedValue))).append('\n')
        }
        line("export_generated_at", Csv.isoDateTime(generatedAtMillis, zone))
        line("date_range", Csv.textField("all_time"))
        line("tax_year", IrsMileage.TAX_YEAR.toString())
        line("irs_business_rate_per_mile", Csv.money(IrsMileage.RATE_PER_MILE, digits = 3))
        line("total_deductible_miles", Csv.decimal(totalMiles))
        line("estimated_mileage_deduction", Csv.money(deduction))
        line("total_sessions", Csv.int(sessions.size))
        line("total_deliveries", Csv.int(deliveries.size))
        line("total_reported_earnings", Csv.money(totalReported))
        line("total_realized_delivery_pay", Csv.money(totalRealized))
        return sb.toString()
    }
}
