package cloud.trotter.dashbuddy.ui.main.analytics

import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * #689 — the MILEAGE & TAX card's pure copy logic: the year-labelled deduction line, the
 * unknown-year honest-fallback disclaimer (copy owned by `IrsMileage.fallbackNote`), and the
 * spans-years note. Since #970 the note (and the labelled year) derive from the selected
 * **[AnalyticsWindows] window's own endpoints**, so the Jan-straddling Monday week and a driver-drawn
 * custom range are both covered, and a paged-back window is priced at ITS year rather than at the
 * device clock's. Compose-free, per the [MoneyWentModel] / `MoneyWentModelTest` precedent (test the
 * logic, not the rendering).
 */
class MileageTaxModelTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    /** Epoch millis for noon on the given date in [utc] — pins the "device clock" per test. */
    private fun noonUtc(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    private fun window(granularity: WindowGranularity, today: LocalDate) =
        AnalyticsWindows.current(granularity, today)

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
        val labels = MileageTaxModel.from(
            100.0, noonUtc(2026, 7, 15), utc,
            window(WindowGranularity.WEEK, LocalDate.of(2026, 7, 15)),
        )
        assertTrue(labels.deductionLine.contains("IRS 2026"))
        assertTrue(labels.deductionLine.contains("$0.725/mi"))
        assertTrue(labels.deductionLine.contains("$72.50")) // 100 * 0.725
        assertNull(labels.disclaimer)
        assertNull(labels.spansYearsNote)
    }

    @Test fun year2025_usesItsOwnRate() {
        val labels = MileageTaxModel.from(
            100.0, noonUtc(2025, 6, 15), utc,
            window(WindowGranularity.DAY, LocalDate.of(2025, 6, 15)),
        )
        assertTrue(labels.deductionLine.contains("IRS 2025"))
        assertTrue(labels.deductionLine.contains("$0.700/mi"))
        assertTrue(labels.deductionLine.contains("$70.00"))
    }

    @Test fun unknownYear_fallsBackToLatestRate_withDisclaimer() {
        val labels = MileageTaxModel.from(
            100.0, noonUtc(2027, 6, 15), utc,
            window(WindowGranularity.MONTH, LocalDate.of(2027, 6, 15)),
        )
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
        val labels = MileageTaxModel.from(100.0, noonUtc(2026, 7, 15), utc, AnalyticsWindows.LIFETIME)
        assertEquals(
            "may span tax years — see the CSV export",
            labels.spansYearsNote,
        )
    }

    @Test fun weekStraddlingNewYear_addsSpansYearsNote() {
        // Thu 2026-01-01 → the Monday-anchored week starts Mon 2025-12-29: the window spans tax
        // years, so the note fires even though the window isn't Lifetime.
        val labels = MileageTaxModel.from(
            100.0, noonUtc(2026, 1, 1), utc,
            window(WindowGranularity.WEEK, LocalDate.of(2026, 1, 1)),
        )
        assertEquals(
            "spans tax years — see the CSV export",
            labels.spansYearsNote,
        )
        // A year-straddling window falls back to the CURRENT year's rate and says so.
        assertTrue(labels.deductionLine.contains("IRS 2026"))
    }

    @Test fun singleYearWindows_haveNoSpansYearsNote() {
        val today = LocalDate.of(2026, 7, 15)
        val midYear = noonUtc(2026, 7, 15)
        assertNull(MileageTaxModel.from(100.0, midYear, utc, window(WindowGranularity.DAY, today)).spansYearsNote)
        assertNull(MileageTaxModel.from(100.0, midYear, utc, window(WindowGranularity.WEEK, today)).spansYearsNote)
        assertNull(MileageTaxModel.from(100.0, midYear, utc, window(WindowGranularity.MONTH, today)).spansYearsNote)
        // Jan 1 itself: Day and Month both start at Jan 1 — same year, no note.
        val newYear = LocalDate.of(2026, 1, 1)
        val newYearsDay = noonUtc(2026, 1, 1)
        assertNull(MileageTaxModel.from(100.0, newYearsDay, utc, window(WindowGranularity.DAY, newYear)).spansYearsNote)
        assertNull(MileageTaxModel.from(100.0, newYearsDay, utc, window(WindowGranularity.MONTH, newYear)).spansYearsNote)
    }

    /**
     * #970 — the pager can select a window in a PAST year. The deduction must then be priced and
     * labelled at THAT year's rate, not at the device clock's: quoting 2026's $0.725 over a window
     * of 2025 miles would be a wrong number wearing a confident label.
     */
    @Test fun pagedBackWindowInAnEarlierYear_isPricedAtThatYearsRate() {
        val labels = MileageTaxModel.from(
            100.0,
            nowMillis = noonUtc(2026, 7, 15), // device clock says 2026 …
            zone = utc,
            window = window(WindowGranularity.MONTH, LocalDate.of(2025, 6, 15)), // … window is June 2025
        )
        assertTrue(labels.deductionLine.contains("IRS 2025"))
        assertTrue(labels.deductionLine.contains("$0.700/mi"))
        assertTrue(labels.deductionLine.contains("$70.00"))
        assertNull(labels.spansYearsNote)
    }

    /** A driver-drawn custom range that crosses New Year gets the same honest note. */
    @Test fun customRangeStraddlingNewYear_addsSpansYearsNote() {
        val labels = MileageTaxModel.from(
            100.0, noonUtc(2026, 7, 15), utc,
            AnalyticsWindows.custom(LocalDate.of(2025, 12, 20), LocalDate.of(2026, 1, 10)),
        )
        assertEquals(
            "spans tax years — see the CSV export",
            labels.spansYearsNote,
        )
    }
}
