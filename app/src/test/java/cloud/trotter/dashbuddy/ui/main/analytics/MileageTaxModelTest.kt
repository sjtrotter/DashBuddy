package cloud.trotter.dashbuddy.ui.main.analytics

import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * #689 — the MILEAGE & TAX card's pure copy logic: the year-labelled deduction line, the
 * unknown-year honest-fallback disclaimer (copy owned by `IrsMileage.fallbackNote`), and the
 * spans-years note derived from the period's real `PeriodBounds` window (so the Jan-straddling
 * Monday week is covered, not just Lifetime). Compose-free, per the [WaterfallModel] /
 * `WaterfallModelTest` precedent (test the logic, not the rendering).
 */
class MileageTaxModelTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    /** Epoch millis for noon on the given date in [utc] — pins the "device clock" per test. */
    private fun noonUtc(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    // Formats.money* pins Locale.getDefault(); fix it (and restore — repo convention, see
    // FormatsTest) so the "$0.725/mi" assertions are deterministic without leaking Locale.US
    // into later test classes in the shared test JVM.
    private lateinit var originalLocale: Locale

    @Before fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test fun knownYear_labelsThatYearsRate_noDisclaimer() {
        val labels = MileageTaxModel.from(100.0, noonUtc(2026, 7, 15), utc, AnalyticsPeriod.THIS_WEEK)
        assertTrue(labels.deductionLine.contains("IRS 2026"))
        assertTrue(labels.deductionLine.contains("$0.725/mi"))
        assertTrue(labels.deductionLine.contains("$72.50")) // 100 * 0.725
        assertNull(labels.disclaimer)
        assertNull(labels.spansYearsNote)
    }

    @Test fun year2025_usesItsOwnRate() {
        val labels = MileageTaxModel.from(100.0, noonUtc(2025, 6, 15), utc, AnalyticsPeriod.TODAY)
        assertTrue(labels.deductionLine.contains("IRS 2025"))
        assertTrue(labels.deductionLine.contains("$0.700/mi"))
        assertTrue(labels.deductionLine.contains("$70.00"))
    }

    @Test fun unknownYear_fallsBackToLatestRate_withDisclaimer() {
        val labels = MileageTaxModel.from(100.0, noonUtc(2027, 6, 15), utc, AnalyticsPeriod.THIS_MONTH)
        // 2027 unpublished → latest known rate (2026 = $0.725/mi), labelled honestly.
        assertTrue(labels.deductionLine.contains("IRS 2027"))
        assertTrue(labels.deductionLine.contains("$0.725/mi"))
        assertTrue(labels.deductionLine.contains("$72.50"))
        assertNull(labels.spansYearsNote)
        assertEquals(
            "2027 rate not yet published — estimated at the 2026 rate",
            labels.disclaimer,
        )
    }

    @Test fun lifetime_addsMaySpanYearsNote() {
        val labels = MileageTaxModel.from(100.0, noonUtc(2026, 7, 15), utc, AnalyticsPeriod.LIFETIME)
        assertEquals(
            "may span tax years — see the CSV export for per-year figures",
            labels.spansYearsNote,
        )
    }

    @Test fun weekStraddlingNewYear_addsSpansYearsNote() {
        // Thu 2026-01-01 → the Monday-anchored week starts Mon 2025-12-29: the window spans tax
        // years, so the note fires even though the period isn't Lifetime.
        val labels = MileageTaxModel.from(100.0, noonUtc(2026, 1, 1), utc, AnalyticsPeriod.THIS_WEEK)
        assertEquals(
            "spans tax years — see the CSV export for per-year figures",
            labels.spansYearsNote,
        )
        // The deduction itself is still labelled (and priced) at the current year's rate.
        assertTrue(labels.deductionLine.contains("IRS 2026"))
    }

    @Test fun singleYearWindows_haveNoSpansYearsNote() {
        val midYear = noonUtc(2026, 7, 15)
        assertNull(MileageTaxModel.from(100.0, midYear, utc, AnalyticsPeriod.TODAY).spansYearsNote)
        assertNull(MileageTaxModel.from(100.0, midYear, utc, AnalyticsPeriod.THIS_WEEK).spansYearsNote)
        assertNull(MileageTaxModel.from(100.0, midYear, utc, AnalyticsPeriod.THIS_MONTH).spansYearsNote)
        // Jan 1 itself: Today and This-Month start at Jan 1 00:00 — same year, no note.
        val newYearsDay = noonUtc(2026, 1, 1)
        assertNull(MileageTaxModel.from(100.0, newYearsDay, utc, AnalyticsPeriod.TODAY).spansYearsNote)
        assertNull(MileageTaxModel.from(100.0, newYearsDay, utc, AnalyticsPeriod.THIS_MONTH).spansYearsNote)
    }
}
