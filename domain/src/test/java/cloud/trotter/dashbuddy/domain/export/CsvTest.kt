package cloud.trotter.dashbuddy.domain.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** RFC-4180 quoting, machine number formatting, ISO timestamps, and the IRS deduction math (#319). */
class CsvTest {

    private val utc = ZoneId.of("UTC")

    // ── RFC-4180 quoting ────────────────────────────────────────────────

    @Test fun plainField_isUnquoted() {
        assertEquals("H-E-B", Csv.field("H-E-B"))
    }

    @Test fun fieldWithComma_isQuoted() {
        assertEquals("\"Chili's, Cedar Park\"", Csv.field("Chili's, Cedar Park"))
    }

    @Test fun fieldWithQuote_doublesTheQuote() {
        assertEquals("\"Joe \"\"The Rock\"\" Bar\"", Csv.field("Joe \"The Rock\" Bar"))
    }

    @Test fun fieldWithNewline_isQuoted() {
        assertEquals("\"line1\nline2\"", Csv.field("line1\nline2"))
    }

    @Test fun fieldWithCarriageReturn_isQuoted() {
        assertEquals("\"a\rb\"", Csv.field("a\rb"))
    }

    @Test fun nullField_isEmpty() {
        assertEquals("", Csv.field(null))
    }

    @Test fun row_quotesEachField() {
        assertEquals(
            "a,\"b,c\",\"d\"\"e\"",
            Csv.row(listOf("a", "b,c", "d\"e")),
        )
    }

    @Test fun row_nullBecomesEmptyField() {
        assertEquals("a,,c", Csv.row(listOf("a", null, "c")))
    }

    // ── Money / decimal (machine, Locale.ROOT, ungrouped) ───────────────

    @Test fun money_twoDecimals_noGrouping() {
        assertEquals("1234.50", Csv.money(1234.5))
    }

    @Test fun money_null_isEmpty_notZero() {
        assertEquals("", Csv.money(null))
    }

    @Test fun money_zero_isFormatted() {
        assertEquals("0.00", Csv.money(0.0))
    }

    @Test fun money_threeDecimals_forCostPerMile() {
        assertEquals("0.165", Csv.money(0.165, digits = 3))
    }

    @Test fun decimal_null_isEmpty() {
        assertEquals("", Csv.decimal(null))
    }

    @Test fun intFormatting_andNull() {
        assertEquals("7", Csv.int(7))
        assertEquals("", Csv.int(null))
    }

    @Test fun millisToMinutes() {
        assertEquals("30.00", Csv.millisToMinutes(1_800_000L))
        assertEquals("", Csv.millisToMinutes(null))
    }

    // ── ISO-8601 timestamps ─────────────────────────────────────────────

    @Test fun isoDate_utc() {
        // 2026-07-05T14:03:27Z
        val ms = 1_783_260_207_000L
        assertEquals("2026-07-05", Csv.isoDate(ms, utc))
    }

    @Test fun isoTime_utc() {
        val ms = 1_783_260_207_000L
        assertEquals("14:03:27", Csv.isoTime(ms, utc))
    }

    @Test fun isoDateTime_utc() {
        val ms = 1_783_260_207_000L
        assertEquals("2026-07-05T14:03:27", Csv.isoDateTime(ms, utc))
    }

    @Test fun isoDateTime_respectsZone() {
        val ms = 1_783_260_207_000L // 14:03:27 UTC → EDT (UTC-4) in July
        assertEquals("2026-07-05T10:03:27", Csv.isoDateTime(ms, ZoneId.of("America/New_York")))
    }

    @Test fun isoTimestamps_null_areEmpty() {
        assertEquals("", Csv.isoDate(null, utc))
        assertEquals("", Csv.isoTime(null, utc))
        assertEquals("", Csv.isoDateTime(null, utc))
    }

    // ── IRS deduction ───────────────────────────────────────────────────

    @Test fun irsRate_isTaxYear2025Business() {
        assertEquals(2025, IrsMileage.TAX_YEAR)
        assertEquals(0.70, IrsMileage.RATE_PER_MILE, 0.0)
    }

    @Test fun irsDeduction_math() {
        assertEquals(70.0, IrsMileage.deduction(100.0), 1e-9)
        assertEquals(0.0, IrsMileage.deduction(0.0), 0.0)
    }
}
