package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * #970 / #1515 — the period bar's label logic: which relation word fronts the window, and how
 * its date range renders. Compose-free.
 *
 * The interesting part is calendar reasoning, not rendering: is this the current week, is it the one
 * before, does the range leave the current year, does it straddle a month. Each of those decides a
 * different string, and a wrong one is a label that lies about which window the numbers came from.
 */
class WindowLabelTest {

    private val wednesday = LocalDate.of(2026, 7, 15)

    // The range strings interpolate a localized month name; pin the locale (and restore it — repo
    // convention, see FormatsTest) so assertions are deterministic in the shared test JVM.
    private lateinit var originalLocale: Locale

    @Before fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun current(granularity: WindowGranularity, today: LocalDate = wednesday) =
        AnalyticsWindows.current(granularity, today)

    // ── relation ────────────────────────────────────────────────────────

    @Test fun `the window containing today is CURRENT`() {
        for (granularity in listOf(WindowGranularity.DAY, WindowGranularity.WEEK, WindowGranularity.MONTH)) {
            assertEquals(
                "$granularity",
                WindowRelation.CURRENT,
                WindowLabel.relation(current(granularity), wednesday),
            )
        }
    }

    @Test fun `one step back is PREVIOUS`() {
        for (granularity in listOf(WindowGranularity.DAY, WindowGranularity.WEEK, WindowGranularity.MONTH)) {
            val window = AnalyticsWindows.step(current(granularity), -1)
            assertEquals("$granularity", WindowRelation.PREVIOUS, WindowLabel.relation(window, wednesday))
        }
    }

    @Test fun `two or more steps back is OLDER`() {
        val window = AnalyticsWindows.step(current(WindowGranularity.WEEK), -2)
        assertEquals(WindowRelation.OLDER, WindowLabel.relation(window, wednesday))
    }

    @Test fun `lifetime reports CURRENT and is titled by its own word`() {
        assertEquals(WindowRelation.CURRENT, WindowLabel.relation(AnalyticsWindows.LIFETIME, wednesday))
    }

    // ── range ───────────────────────────────────────────────────────────

    @Test fun `lifetime has no range`() {
        assertNull(WindowLabel.range(AnalyticsWindows.LIFETIME, wednesday))
    }

    @Test fun `a week inside one month renders as a compact day span`() {
        // Mon 2026-07-13 – Sun 2026-07-19.
        assertEquals(RangeParts("Jul 13", "19"), WindowLabel.range(current(WindowGranularity.WEEK), wednesday))
    }

    @Test fun `a week straddling two months names both months`() {
        // Mon 2026-06-29 – Sun 2026-07-05.
        val window = AnalyticsWindows.current(WindowGranularity.WEEK, LocalDate.of(2026, 7, 1))
        assertEquals(RangeParts("Jun 29", "Jul 5"), WindowLabel.range(window, wednesday))
    }

    @Test fun `a day window renders as a single date`() {
        assertEquals(RangeParts("Jul 15", null), WindowLabel.range(current(WindowGranularity.DAY), wednesday))
    }

    @Test fun `a month window in the current year renders as the bare month name`() {
        assertEquals(RangeParts("July", null), WindowLabel.range(current(WindowGranularity.MONTH), wednesday))
    }

    /** The year is stated as soon as the window leaves today's year — never ambiguous when paged back. */
    @Test fun `a month window in another year carries the year`() {
        val window = AnalyticsWindows.current(WindowGranularity.MONTH, LocalDate.of(2025, 11, 4))
        assertEquals(RangeParts("November 2025", null), WindowLabel.range(window, wednesday))
    }

    @Test fun `a week entirely in a previous year carries the year on its end`() {
        val window = AnalyticsWindows.current(WindowGranularity.WEEK, LocalDate.of(2025, 3, 5))
        assertEquals(RangeParts("Mar 3", "Mar 9, 2025"), WindowLabel.range(window, wednesday))
    }

    /** A week that straddles New Year states BOTH years: the one case a single year would mislead. */
    @Test fun `a week straddling New Year carries both years`() {
        val window = AnalyticsWindows.current(WindowGranularity.WEEK, LocalDate.of(2025, 12, 31))
        assertEquals(RangeParts("Dec 29, 2025", "Jan 4, 2026"), WindowLabel.range(window, wednesday))
    }
}
