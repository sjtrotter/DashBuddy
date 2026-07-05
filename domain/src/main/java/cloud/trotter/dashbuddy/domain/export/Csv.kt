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
 * **Cell typing is the injection defense.** The API splits cells into two kinds so the call
 * site's type choice enforces the guard:
 *
 *  - [textField] — for **text** cells, above all untrusted third-party strings (store/merchant
 *    names come from another app's UI tree). Applies RFC-4180 quoting AND **formula-injection
 *    neutralization**: spreadsheets strip the RFC-4180 quotes on import and then interpret a
 *    cell starting with `=`, `+`, `-`, `@`, TAB, or CR as a *formula* (CSV injection), so a
 *    leading dangerous character is prefixed with `'` (the standard force-text marker). A store
 *    named `=cmd(...)` lands in the sheet as literal text, never executes.
 *  - The **numeric/timestamp emitters** ([money], [decimal], [int], [isoDate], [isoTime],
 *    [isoDateTime], [millisToMinutes]) — safe **by construction**: they only ever format
 *    program-generated numbers/instants into `[-0-9.T:]` strings (no comma/quote/newline, never
 *    a formula payload), so they are NOT `'`-prefixed — a negative amount stays a bare,
 *    machine-parseable `-2.50`. Never route free text through these.
 *
 * [row] joins **already-encoded** cells — every cell must come from [textField] or a numeric
 * emitter; it applies no escaping of its own (re-escaping would corrupt quoted cells).
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

    /** Leading characters a spreadsheet interprets as the start of a formula (CSV injection). */
    private val FORMULA_LEADERS = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /**
     * Encode one **text** cell: neutralize a leading formula character (`'` prefix), then
     * RFC-4180 quote. Use for every human/text column — REQUIRED for anything untrusted
     * (third-party UI strings). `null` ⇒ empty cell (distinguishes missing from empty content).
     */
    fun textField(value: String?): String {
        if (value == null) return ""
        val neutralized =
            if (value.isNotEmpty() && value[0] in FORMULA_LEADERS) "'$value" else value
        return quote(neutralized)
    }

    /** RFC-4180 quote a cell's content ([textField] is the public text entry point). */
    private fun quote(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /**
     * Assemble one CSV record from **already-encoded** cells (each from [textField] or a
     * numeric/timestamp emitter). Applies no escaping of its own.
     */
    fun row(cells: List<String>): String = cells.joinToString(",")

    /** Ungrouped, Locale.ROOT decimal money. `null` ⇒ empty cell (missing ≠ `0.00`). */
    fun money(value: Double?, digits: Int = 2): String =
        if (value == null) "" else String.format(Locale.ROOT, "%.${digits}f", value)

    /** Ungrouped, Locale.ROOT decimal. `null` ⇒ empty cell. */
    fun decimal(value: Double?, digits: Int = 2): String =
        if (value == null) "" else String.format(Locale.ROOT, "%.${digits}f", value)

    /** Plain integer, no grouping. `null` ⇒ empty cell. */
    fun int(value: Int?): String = value?.toString() ?: ""

    /** ISO-8601 local date (`2026-07-05`) in [zone]. `null` ⇒ empty cell. */
    fun isoDate(epochMillis: Long?, zone: ZoneId): String =
        if (epochMillis == null) "" else DATE.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** ISO-8601 local time (`14:03:27`) in [zone]. `null` ⇒ empty cell. */
    fun isoTime(epochMillis: Long?, zone: ZoneId): String =
        if (epochMillis == null) "" else TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** ISO-8601 local date-time (`2026-07-05T14:03:27`) in [zone]. `null` ⇒ empty cell. */
    fun isoDateTime(epochMillis: Long?, zone: ZoneId): String =
        if (epochMillis == null) "" else DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** Milliseconds → decimal minutes (2 dp). `null` ⇒ empty cell. */
    fun millisToMinutes(millis: Long?, digits: Int = 2): String =
        if (millis == null) "" else String.format(Locale.ROOT, "%.${digits}f", millis / 60_000.0)
}
