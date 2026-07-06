package cloud.trotter.dashbuddy.ui.main.analytics

import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * #689 — the MILEAGE & TAX card's pure copy logic: the year-labelled deduction line, the
 * unknown-year honest-fallback disclaimer, and the Lifetime spans-years note. Compose-free, per the
 * [WaterfallModel] / `WaterfallModelTest` precedent (test the logic, not the rendering).
 */
class MileageTaxModelTest {

    // Formats.money* pins Locale.getDefault(); fix it so the "$0.725/mi" assertions are deterministic.
    @Before fun enUsLocale() = Locale.setDefault(Locale.US)

    @Test fun knownYear_labelsThatYearsRate_noDisclaimer() {
        val labels = MileageTaxModel.from(miles = 100.0, currentYear = 2026, period = AnalyticsPeriod.THIS_WEEK)
        assertTrue(labels.deductionLine.contains("IRS 2026"))
        assertTrue(labels.deductionLine.contains("$0.725/mi"))
        assertTrue(labels.deductionLine.contains("$72.50")) // 100 * 0.725
        assertNull(labels.disclaimer)
        assertNull(labels.spansYearsNote)
    }

    @Test fun year2025_usesItsOwnRate() {
        val labels = MileageTaxModel.from(100.0, 2025, AnalyticsPeriod.TODAY)
        assertTrue(labels.deductionLine.contains("IRS 2025"))
        assertTrue(labels.deductionLine.contains("$0.700/mi"))
        assertTrue(labels.deductionLine.contains("$70.00"))
    }

    @Test fun unknownYear_fallsBackToLatestRate_withDisclaimer() {
        val labels = MileageTaxModel.from(100.0, 2027, AnalyticsPeriod.THIS_MONTH)
        // 2027 unpublished → latest known rate (2026 = $0.725/mi), labelled honestly.
        assertTrue(labels.deductionLine.contains("IRS 2027"))
        assertTrue(labels.deductionLine.contains("$0.725/mi"))
        assertTrue(labels.deductionLine.contains("$72.50"))
        assertNull(labels.spansYearsNote)
        org.junit.Assert.assertEquals(
            "2027 rate not yet published — estimated at the 2026 rate",
            labels.disclaimer,
        )
    }

    @Test fun lifetime_addsSpansYearsNote() {
        val labels = MileageTaxModel.from(100.0, 2026, AnalyticsPeriod.LIFETIME)
        org.junit.Assert.assertEquals(
            "spans tax years — see the CSV export for per-year figures",
            labels.spansYearsNote,
        )
    }

    @Test fun nonLifetime_hasNoSpansYearsNote() {
        assertNull(MileageTaxModel.from(100.0, 2026, AnalyticsPeriod.TODAY).spansYearsNote)
        assertNull(MileageTaxModel.from(100.0, 2026, AnalyticsPeriod.THIS_WEEK).spansYearsNote)
        assertNull(MileageTaxModel.from(100.0, 2026, AnalyticsPeriod.THIS_MONTH).spansYearsNote)
    }
}
