package cloud.trotter.dashbuddy.domain.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * RFC-4180 quoting, formula-injection neutralization, machine number formatting, ISO timestamps,
 * and the IRS deduction math (#319).
 */
class CsvTest {
    private val utc = ZoneId.of("UTC")

    @Test fun `text quoting and formula injection cases`() {
        listOf(
            "H-E-B" to "H-E-B",
            "Chili's, Cedar Park" to "\"Chili's, Cedar Park\"",
            "Joe \"The Rock\" Bar" to "\"Joe \"\"The Rock\"\" Bar\"",
            "line1\nline2" to "\"line1\nline2\"",
            null to "",
            "=cmd()" to "'=cmd()",
            "+1+1" to "'+1+1",
            "-2+3" to "'-2+3",
            "@SUM(A1)" to "'@SUM(A1)",
            "\tX" to "'\tX",
            "\ra" to "\"'\ra\"",
            "=a,b" to "\"'=a,b\"",
            "H-E-B Plus=1" to "H-E-B Plus=1",
        ).forEachIndexed { index, (input, expected) ->
            assertEquals("text row $index input=<$input>", expected, Csv.textField(input))
        }
    }

    @Test fun `encoded row assembly`() {
        listOf(
            listOf("a", "b,c", "d\"e") to "a,\"b,c\",\"d\"\"e\"",
            listOf("a", null, "c") to "a,,c",
        ).forEach { (cells, expected) ->
            assertEquals("cells=$cells", expected, Csv.row(cells.map(Csv::textField)))
        }
    }

    private data class FormatCase(val name: String, val expected: String, val format: () -> String)

    @Test fun `machine numbers remain bare and nulls remain empty`() {
        listOf(
            FormatCase("Csv.money(-2.5)", "-2.50") { Csv.money(-2.5) },
            FormatCase("Csv.decimal(-4.2)", "-4.20") { Csv.decimal(-4.2) },
            FormatCase("Csv.int(-3)", "-3") { Csv.int(-3) },
            FormatCase("Csv.money(1234.5)", "1234.50") { Csv.money(1234.5) },
            FormatCase("Csv.money(null)", "") { Csv.money(null) },
            FormatCase("Csv.money(0.0)", "0.00") { Csv.money(0.0) },
            FormatCase("Csv.money(0.165, digits = 3)", "0.165") { Csv.money(0.165, digits = 3) },
            FormatCase("Csv.decimal(null)", "") { Csv.decimal(null) },
            FormatCase("Csv.int(7)", "7") { Csv.int(7) },
            FormatCase("Csv.int(null)", "") { Csv.int(null) },
            FormatCase("Csv.millisToMinutes(1_800_000L)", "30.00") { Csv.millisToMinutes(1_800_000L) },
            FormatCase("Csv.millisToMinutes(null)", "") { Csv.millisToMinutes(null) },
        ).forEach { (name, expected, format) -> assertEquals(name, expected, format()) }
    }

    @Test fun `ISO timestamps respect the zone and preserve nulls`() {
        val ms = 1_783_260_207_000L // 2026-07-05T14:03:27Z
        listOf(
            FormatCase("UTC date", "2026-07-05") { Csv.isoDate(ms, utc) },
            FormatCase("UTC time", "14:03:27") { Csv.isoTime(ms, utc) },
            FormatCase("UTC date time", "2026-07-05T14:03:27") { Csv.isoDateTime(ms, utc) },
            FormatCase("New York date time", "2026-07-05T10:03:27") {
                Csv.isoDateTime(ms, ZoneId.of("America/New_York"))
            },
            FormatCase("null date", "") { Csv.isoDate(null, utc) },
            FormatCase("null time", "") { Csv.isoTime(null, utc) },
            FormatCase("null date time", "") { Csv.isoDateTime(null, utc) },
        ).forEach { (name, expected, format) -> assertEquals(name, expected, format()) }
    }

    @Test fun `IRS published rates and fallback disclosure`() {
        data class Case(val year: Int, val rate: Double?, val effective: Double, val note: String?)
        listOf(
            Case(2025, 0.70, 0.70, null),
            Case(2026, 0.725, 0.725, null),
            Case(2027, null, 0.725, "2027 rate unavailable in DashBuddy; estimate uses 2026 rate."),
            Case(2024, null, 0.725, "2024 rate unavailable in DashBuddy; estimate uses 2026 rate."),
        ).forEach { (year, rate, effective, note) ->
            val name = "year=$year"
            assertEquals(name, rate, IrsMileage.rateFor(year))
            assertEquals("$name known", rate != null, IrsMileage.isKnown(year))
            assertEquals("$name effective", effective, IrsMileage.effectiveRate(year), 0.0)
            assertEquals("$name note", note, IrsMileage.fallbackNote(year))
        }
        assertEquals("latest published rate", 2026 to 0.725, IrsMileage.latestKnown())
    }

    @Test fun `IRS deductions use the year rate or latest fallback`() {
        data class Case(val miles: Double, val year: Int, val expected: Double, val delta: Double)
        listOf(
            Case(100.0, 2025, 70.0, 1e-9),
            Case(100.0, 2026, 72.5, 1e-9),
            Case(0.0, 2025, 0.0, 0.0),
            Case(100.0, 2027, 72.5, 1e-9),
        ).forEach { (miles, year, expected, delta) ->
            assertEquals("miles=$miles year=$year", expected, IrsMileage.deduction(miles, year), delta)
        }
    }
}
