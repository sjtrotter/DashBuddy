package cloud.trotter.dashbuddy.domain.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure, machine-readable CSV primitives for the free-tier data export (#319).
 *
 * This is deliberately NOT the human-facing [cloud.trotter.dashbuddy.domain.format.Formats]
 * locale policy: an export is a **machine string** (a spreadsheet / a tax preparer's tool
 * parses it back), so everything here pins [Locale.ROOT] and emits ungrouped decimals — no
 * thousands separators, no `$`, no locale decimal comma. A German-locale device must still
 * write `1234.50`, not `1.234,50`, or the CSV is unparseable. Timestamps are ISO-8601 in the
 * device's local zone (passed in — pure, no `ZoneId.systemDefault()` call baked here so it is
 * unit-testable).
 *
 * Quoting is RFC-4180: a field containing a comma, a double-quote, or a newline is wrapped in
 * double-quotes and its own quotes are doubled. Store/merchant names routinely contain commas
 * ("Chili's Grill & Bar, Cedar Park"), so this is load-bearing, not decorative.
 *
 * Lives in `:domain` (pure Kotlin, no Android) so the `:core:data` exporter and its unit tests
 * both route through one definition (Principle 5).
 */
object Csv {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    /** RFC-4180 quote a single field. `null` ⇒ empty field (distinguishes missing from `0`). */
    fun field(value: String?): String {
        if (value == null) return ""
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /** Assemble one CSV record from raw (unquoted) fields; each is RFC-4180 quoted. */
    fun row(values: List<String?>): String = values.joinToString(",") { field(it) }

    /** Ungrouped, Locale.ROOT decimal money. `null` ⇒ empty field (missing ≠ `0.00`). */
    fun money(value: Double?, digits: Int = 2): String =
        if (value == null) "" else String.format(Locale.ROOT, "%.${digits}f", value)

    /** Ungrouped, Locale.ROOT decimal. `null` ⇒ empty field. */
    fun decimal(value: Double?, digits: Int = 2): String =
        if (value == null) "" else String.format(Locale.ROOT, "%.${digits}f", value)

    /** Plain integer, no grouping. `null` ⇒ empty field. */
    fun int(value: Int?): String = value?.toString() ?: ""

    /** ISO-8601 local date (`2026-07-05`) in [zone]. `null` ⇒ empty field. */
    fun isoDate(epochMillis: Long?, zone: ZoneId): String =
        if (epochMillis == null) "" else DATE.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** ISO-8601 local time (`14:03:27`) in [zone]. `null` ⇒ empty field. */
    fun isoTime(epochMillis: Long?, zone: ZoneId): String =
        if (epochMillis == null) "" else TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** ISO-8601 local date-time (`2026-07-05T14:03:27`) in [zone]. `null` ⇒ empty field. */
    fun isoDateTime(epochMillis: Long?, zone: ZoneId): String =
        if (epochMillis == null) "" else DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** Milliseconds → decimal minutes (2 dp). `null` ⇒ empty field. */
    fun millisToMinutes(millis: Long?, digits: Int = 2): String =
        if (millis == null) "" else String.format(Locale.ROOT, "%.${digits}f", millis / 60_000.0)
}
