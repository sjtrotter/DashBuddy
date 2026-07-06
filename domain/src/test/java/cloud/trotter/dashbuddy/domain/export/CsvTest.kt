package cloud.trotter.dashbuddy.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * RFC-4180 quoting, formula-injection neutralization, machine number formatting, ISO timestamps,
 * and the IRS deduction math (#319).
 */
class CsvTest {

    private val utc = ZoneId.of("UTC")

    // ── RFC-4180 quoting (textField) ────────────────────────────────────

    @Test fun plainField_isUnquoted() {
        assertEquals("H-E-B", Csv.textField("H-E-B"))
    }

    @Test fun fieldWithComma_isQuoted() {
        assertEquals("\"Chili's, Cedar Park\"", Csv.textField("Chili's, Cedar Park"))
    }

    @Test fun fieldWithQuote_doublesTheQuote() {
        assertEquals("\"Joe \"\"The Rock\"\" Bar\"", Csv.textField("Joe \"The Rock\" Bar"))
    }

    @Test fun fieldWithNewline_isQuoted() {
        assertEquals("\"line1\nline2\"", Csv.textField("line1\nline2"))
    }

    @Test fun nullField_isEmpty() {
        assertEquals("", Csv.textField(null))
    }

    // ── Formula-injection neutralization (textField only) ───────────────

    @Test fun leadingEquals_isNeutralized() {
        assertEquals("'=cmd()", Csv.textField("=cmd()"))
    }

    @Test fun leadingPlus_isNeutralized() {
        assertEquals("'+1+1", Csv.textField("+1+1"))
    }

    @Test fun leadingMinus_isNeutralized() {
        assertEquals("'-2+3", Csv.textField("-2+3"))
    }

    @Test fun leadingAt_isNeutralized() {
        assertEquals("'@SUM(A1)", Csv.textField("@SUM(A1)"))
    }

    @Test fun leadingTab_isNeutralized() {
        assertEquals("'\tX", Csv.textField("\tX"))
    }

    @Test fun leadingCarriageReturn_isNeutralizedAndQuoted() {
        // CR is both a formula leader AND an RFC-4180 quote trigger: '-prefixed, then quoted.
        assertEquals("\"'\ra\"", Csv.textField("\ra"))
    }

    @Test fun neutralizedFieldWithComma_isAlsoQuoted() {
        assertEquals("\"'=a,b\"", Csv.textField("=a,b"))
    }

    @Test fun interiorFormulaChars_areNotNeutralized() {
        // Only a LEADING dangerous char triggers the guard.
        assertEquals("H-E-B Plus=1", Csv.textField("H-E-B Plus=1"))
    }

    @Test fun numericEmitters_stayBare_negativeMoneyNotNeutralized() {
        // Program-generated numbers are safe by construction and must stay machine-parseable.
        assertEquals("-2.50", Csv.money(-2.5))
        assertEquals("-4.20", Csv.decimal(-4.2))
        assertEquals("-3", Csv.int(-3))
    }

    // ── Row assembly (pre-encoded cells) ────────────────────────────────

    @Test fun row_joinsEncodedCells() {
        assertEquals(
            "a,\"b,c\",\"d\"\"e\"",
            Csv.row(listOf(Csv.textField("a"), Csv.textField("b,c"), Csv.textField("d\"e"))),
        )
    }

    @Test fun row_nullTextBecomesEmptyCell() {
        assertEquals("a,,c", Csv.row(listOf(Csv.textField("a"), Csv.textField(null), Csv.textField("c"))))
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

    // ── IRS per-year mileage rates (#689) ───────────────────────────────

    @Test fun irsRate_knownYears() {
        assertEquals(0.70, IrsMileage.rateFor(2025)!!, 0.0)
        assertEquals(0.725, IrsMileage.rateFor(2026)!!, 0.0)
    }

    @Test fun irsRate_unknownYear_isNull() {
        assertNull(IrsMileage.rateFor(2027))
        assertNull(IrsMileage.rateFor(2024))
    }

    @Test fun irsIsKnown_tracksThePublishedTable() {
        assertTrue(IrsMileage.isKnown(2025))
        assertTrue(IrsMileage.isKnown(2026))
        assertFalse(IrsMileage.isKnown(2027))
    }

    @Test fun irsLatestKnown_isTheMaxYearsRate() {
        assertEquals(2026 to 0.725, IrsMileage.latestKnown())
    }

    @Test fun irsDeduction_perYearRate() {
        assertEquals(70.0, IrsMileage.deduction(100.0, 2025), 1e-9)
        assertEquals(72.5, IrsMileage.deduction(100.0, 2026), 1e-9)
        assertEquals(0.0, IrsMileage.deduction(0.0, 2025), 0.0)
    }

    @Test fun irsDeduction_unknownYear_fallsBackToLatestKnownRate() {
        // 2027 has no published rate → latest known (2026 = $0.725/mi).
        assertEquals(72.5, IrsMileage.deduction(100.0, 2027), 1e-9)
    }
}
