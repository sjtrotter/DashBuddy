package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * #970 — the pure window math behind the analytics period pager (brief §3.1/§7.2).
 *
 * The load-bearing properties, all of which the pager and the recap hero's delta chip depend on:
 *  - **Monday weeks.** A window's week starts Monday regardless of which weekday `today` is — the
 *    same pay-week anchor `PeriodBounds` has always used.
 *  - **Calendar months.** Stepping and the previous-equivalent window are *calendar* operations, so
 *    March's predecessor is February (28 **or 29** days), and January's is the previous December.
 *  - **Forward is fenced.** `canStepForward` is false at (and past) the window containing today, so
 *    the pager can never select a future range.
 *  - **Selections resolve relatively.** A `Relative` selection re-resolves against whatever `today`
 *    is, which is what makes "this week" still mean this week after the app restarts a week later.
 */
class AnalyticsWindowsTest {

    // A Wednesday, mid-month, mid-year — deliberately not a boundary, so a test that only passes on
    // an edge case can't hide.
    private val wednesday = LocalDate.of(2026, 7, 15)

    // ── current() ───────────────────────────────────────────────────────

    @Test fun `current day window is the single day containing today`() {
        val window = AnalyticsWindows.current(WindowGranularity.DAY, wednesday)
        assertEquals(wednesday, window.startDate)
        assertEquals(wednesday, window.endDateInclusive)
        assertEquals(1L, window.lengthDays)
    }

    @Test fun `current week window is Monday-anchored whatever weekday today is`() {
        // Every day of the week resolves to the same Mon 2026-07-13 – Sun 2026-07-19 window.
        val monday = LocalDate.of(2026, 7, 13)
        for (offset in 0L..6L) {
            val window = AnalyticsWindows.current(WindowGranularity.WEEK, monday.plusDays(offset))
            assertEquals(monday, window.startDate)
            assertEquals(DayOfWeek.MONDAY, window.startDate!!.dayOfWeek)
            assertEquals(LocalDate.of(2026, 7, 19), window.endDateInclusive)
            assertEquals(7L, window.lengthDays)
        }
    }

    /** A Sunday must belong to the week that STARTED on Monday, not open a new one. */
    @Test fun `sunday belongs to the week that started the previous monday`() {
        val window = AnalyticsWindows.current(WindowGranularity.WEEK, LocalDate.of(2026, 7, 19))
        assertEquals(LocalDate.of(2026, 7, 13), window.startDate)
    }

    @Test fun `current month window is the whole calendar month`() {
        val window = AnalyticsWindows.current(WindowGranularity.MONTH, wednesday)
        assertEquals(LocalDate.of(2026, 7, 1), window.startDate)
        assertEquals(LocalDate.of(2026, 7, 31), window.endDateInclusive)
        assertEquals(31L, window.lengthDays)
    }

    @Test fun `lifetime is unbounded and has no length`() {
        val window = AnalyticsWindows.current(WindowGranularity.LIFETIME, wednesday)
        assertTrue(window.isLifetime)
        assertNull(window.startDate)
        assertNull(window.endDateExclusive)
        assertNull(window.lengthDays)
        assertTrue(window.contains(LocalDate.of(1999, 1, 1)))
    }

    @Test fun `CUSTOM has no current window`() {
        assertThrows(IllegalStateException::class.java) {
            AnalyticsWindows.current(WindowGranularity.CUSTOM, wednesday)
        }
    }

    // ── step() ──────────────────────────────────────────────────────────

    @Test fun `stepping a week back lands on the previous Monday week`() {
        val stepped = AnalyticsWindows.step(AnalyticsWindows.current(WindowGranularity.WEEK, wednesday), -1)
        assertEquals(LocalDate.of(2026, 7, 6), stepped.startDate)
        assertEquals(LocalDate.of(2026, 7, 12), stepped.endDateInclusive)
    }

    @Test fun `stepping a day back lands on yesterday`() {
        val stepped = AnalyticsWindows.step(AnalyticsWindows.current(WindowGranularity.DAY, wednesday), -1)
        assertEquals(LocalDate.of(2026, 7, 14), stepped.startDate)
        assertEquals(1L, stepped.lengthDays)
    }

    /** A month step is a CALENDAR step: from a 31-day month back into a 30-day one. */
    @Test fun `stepping a month back lands on the whole previous calendar month`() {
        val stepped = AnalyticsWindows.step(AnalyticsWindows.current(WindowGranularity.MONTH, wednesday), -1)
        assertEquals(LocalDate.of(2026, 6, 1), stepped.startDate)
        assertEquals(LocalDate.of(2026, 6, 30), stepped.endDateInclusive)
        assertEquals(30L, stepped.lengthDays)
    }

    @Test fun `stepping a month back across January lands in the previous December`() {
        val january = AnalyticsWindows.current(WindowGranularity.MONTH, LocalDate.of(2026, 1, 10))
        val stepped = AnalyticsWindows.step(january, -1)
        assertEquals(LocalDate.of(2025, 12, 1), stepped.startDate)
        assertEquals(LocalDate.of(2025, 12, 31), stepped.endDateInclusive)
    }

    @Test fun `a custom window steps by its own length and keeps that length`() {
        val window = AnalyticsWindows.custom(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 14)) // 5 days
        val back = AnalyticsWindows.step(window, -1)
        assertEquals(LocalDate.of(2026, 7, 5), back.startDate)
        assertEquals(LocalDate.of(2026, 7, 9), back.endDateInclusive)
        assertEquals(5L, back.lengthDays)
        assertEquals(WindowGranularity.CUSTOM, back.granularity)
    }

    @Test fun `lifetime never steps`() {
        assertEquals(AnalyticsWindows.LIFETIME, AnalyticsWindows.step(AnalyticsWindows.LIFETIME, -3))
        assertEquals(AnalyticsWindows.LIFETIME, AnalyticsWindows.step(AnalyticsWindows.LIFETIME, 3))
    }

    @Test fun `stepping by zero is the identity`() {
        val window = AnalyticsWindows.current(WindowGranularity.WEEK, wednesday)
        assertEquals(window, AnalyticsWindows.step(window, 0))
    }

    /** Stepping back N then forward N must return the original window — no drift across month lengths. */
    @Test fun `stepping back then forward round-trips for every granularity`() {
        for (granularity in listOf(WindowGranularity.DAY, WindowGranularity.WEEK, WindowGranularity.MONTH)) {
            val window = AnalyticsWindows.current(granularity, wednesday)
            for (steps in 1..14) {
                val roundTrip = AnalyticsWindows.step(AnalyticsWindows.step(window, -steps), steps)
                assertEquals("$granularity step $steps", window, roundTrip)
            }
        }
    }

    // ── previous() — the delta chip's comparison window (§7.2) ──────────

    @Test fun `previous week is the immediately preceding Monday week`() {
        val previous = AnalyticsWindows.previous(AnalyticsWindows.current(WindowGranularity.WEEK, wednesday))!!
        assertEquals(LocalDate.of(2026, 7, 6), previous.startDate)
        assertEquals(LocalDate.of(2026, 7, 12), previous.endDateInclusive)
        assertEquals(7L, previous.lengthDays)
    }

    /** The month-boundary case the brief calls out: March's predecessor is a SHORTER February. */
    @Test fun `previous month of March is February with its real length`() {
        val march2026 = AnalyticsWindows.current(WindowGranularity.MONTH, LocalDate.of(2026, 3, 15))
        val previous = AnalyticsWindows.previous(march2026)!!
        assertEquals(LocalDate.of(2026, 2, 1), previous.startDate)
        assertEquals(LocalDate.of(2026, 2, 28), previous.endDateInclusive)
        assertEquals(28L, previous.lengthDays)
    }

    /** …and in a leap year it is 29 days, because the step is calendar-aware, not a fixed subtraction. */
    @Test fun `previous month of March in a leap year is a 29-day February`() {
        val march2028 = AnalyticsWindows.current(WindowGranularity.MONTH, LocalDate.of(2028, 3, 15))
        val previous = AnalyticsWindows.previous(march2028)!!
        assertEquals(LocalDate.of(2028, 2, 1), previous.startDate)
        assertEquals(LocalDate.of(2028, 2, 29), previous.endDateInclusive)
        assertEquals(29L, previous.lengthDays)
    }

    @Test fun `previous of a January month window is the previous December`() {
        val january = AnalyticsWindows.current(WindowGranularity.MONTH, LocalDate.of(2026, 1, 31))
        val previous = AnalyticsWindows.previous(january)!!
        assertEquals(LocalDate.of(2025, 12, 1), previous.startDate)
        assertEquals(LocalDate.of(2025, 12, 31), previous.endDateInclusive)
    }

    @Test fun `previous of a day window is yesterday`() {
        val previous = AnalyticsWindows.previous(AnalyticsWindows.current(WindowGranularity.DAY, wednesday))!!
        assertEquals(LocalDate.of(2026, 7, 14), previous.startDate)
    }

    /** A custom span compares against the immediately preceding span of the SAME length. */
    @Test fun `previous of a custom window is the equally long span immediately before it`() {
        val window = AnalyticsWindows.custom(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 16)) // 7 days
        val previous = AnalyticsWindows.previous(window)!!
        assertEquals(LocalDate.of(2026, 7, 3), previous.startDate)
        assertEquals(LocalDate.of(2026, 7, 9), previous.endDateInclusive)
        assertEquals(window.lengthDays, previous.lengthDays)
    }

    /** Lifetime has no predecessor — the hero must say so rather than fabricate a comparison (§9). */
    @Test fun `lifetime has no previous window`() {
        assertNull(AnalyticsWindows.previous(AnalyticsWindows.LIFETIME))
    }

    /** A previous window must abut its successor exactly — no gap, no overlap, for any granularity. */
    @Test fun `previous window abuts the current one exactly`() {
        for (granularity in listOf(WindowGranularity.DAY, WindowGranularity.WEEK, WindowGranularity.MONTH)) {
            val window = AnalyticsWindows.current(granularity, wednesday)
            val previous = AnalyticsWindows.previous(window)!!
            assertEquals("$granularity", window.startDate, previous.endDateExclusive)
        }
        val custom = AnalyticsWindows.custom(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 16))
        assertEquals(custom.startDate, AnalyticsWindows.previous(custom)!!.endDateExclusive)
    }

    // ── canStepForward / canStepBack — the arrow fences ─────────────────

    @Test fun `forward is disabled at the current window and enabled once paged back`() {
        for (granularity in listOf(WindowGranularity.DAY, WindowGranularity.WEEK, WindowGranularity.MONTH)) {
            val current = AnalyticsWindows.current(granularity, wednesday)
            assertFalse("$granularity", AnalyticsWindows.canStepForward(current, wednesday))
            assertTrue("$granularity", AnalyticsWindows.canStepForward(AnalyticsWindows.step(current, -1), wednesday))
        }
    }

    /** The fence is on the END bound, so a window merely CONTAINING today still can't page forward. */
    @Test fun `a custom window that reaches today cannot page forward`() {
        val spansToday = AnalyticsWindows.custom(wednesday.minusDays(3), wednesday)
        assertFalse(AnalyticsWindows.canStepForward(spansToday, wednesday))
        val endsYesterday = AnalyticsWindows.custom(wednesday.minusDays(3), wednesday.minusDays(1))
        assertTrue(AnalyticsWindows.canStepForward(endsYesterday, wednesday))
    }

    @Test fun `lifetime pages in neither direction`() {
        assertFalse(AnalyticsWindows.canStepForward(AnalyticsWindows.LIFETIME, wednesday))
        assertFalse(AnalyticsWindows.canStepBack(AnalyticsWindows.LIFETIME))
    }

    @Test fun `every non-lifetime window can page back`() {
        for (granularity in listOf(WindowGranularity.DAY, WindowGranularity.WEEK, WindowGranularity.MONTH)) {
            assertTrue(AnalyticsWindows.canStepBack(AnalyticsWindows.current(granularity, wednesday)))
        }
        assertTrue(AnalyticsWindows.canStepBack(AnalyticsWindows.custom(wednesday, wednesday)))
    }

    // ── custom() + contains() ───────────────────────────────────────────

    @Test fun `a custom range is inclusive of both endpoints`() {
        val window = AnalyticsWindows.custom(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12))
        assertTrue(window.contains(LocalDate.of(2026, 7, 10)))
        assertTrue(window.contains(LocalDate.of(2026, 7, 12)))
        assertFalse(window.contains(LocalDate.of(2026, 7, 13)))
        assertFalse(window.contains(LocalDate.of(2026, 7, 9)))
        assertEquals(3L, window.lengthDays)
    }

    @Test fun `a single-day custom range is legal`() {
        val window = AnalyticsWindows.custom(wednesday, wednesday)
        assertEquals(1L, window.lengthDays)
    }

    @Test fun `a backwards custom range is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsWindows.custom(LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 10))
        }
    }

    @Test fun `an empty window is structurally impossible`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsWindow(WindowGranularity.CUSTOM, wednesday, wednesday)
        }
    }

    // ── AnalyticsWindowSelection ────────────────────────────────────────

    @Test fun `the default selection is the current pay week`() {
        assertEquals(
            AnalyticsWindows.current(WindowGranularity.WEEK, wednesday),
            AnalyticsWindowSelection.DEFAULT.resolve(wednesday),
        )
    }

    /**
     * The reason a relative selection is persisted instead of an absolute window: reopening the hub
     * a week later must resolve "this week" to the NEW week, not restore a stale range.
     */
    @Test fun `a relative selection re-resolves against whatever today is`() {
        val selection = AnalyticsWindowSelection.Relative(WindowGranularity.WEEK, 0)
        assertEquals(LocalDate.of(2026, 7, 13), selection.resolve(wednesday).startDate)
        assertEquals(LocalDate.of(2026, 7, 20), selection.resolve(wednesday.plusWeeks(1)).startDate)
    }

    @Test fun `a relative offset resolves to that many windows back`() {
        val selection = AnalyticsWindowSelection.Relative(WindowGranularity.WEEK, -2)
        assertEquals(LocalDate.of(2026, 6, 29), selection.resolve(wednesday).startDate)
    }

    /** A custom selection is absolute — it must come back verbatim no matter when it is resolved. */
    @Test fun `a custom selection resolves to the same span regardless of today`() {
        val selection = AnalyticsWindowSelection.Custom(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 8))
        val fromJuly = selection.resolve(wednesday)
        val fromNextYear = selection.resolve(LocalDate.of(2027, 1, 1))
        assertEquals(fromJuly, fromNextYear)
        assertEquals(LocalDate.of(2026, 3, 2), fromJuly.startDate)
        assertEquals(LocalDate.of(2026, 3, 8), fromJuly.endDateInclusive)
    }

    @Test fun `a relative selection cannot be CUSTOM`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsWindowSelection.Relative(WindowGranularity.CUSTOM, 0)
        }
    }

    @Test fun `a backwards custom selection is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalyticsWindowSelection.Custom(LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 10))
        }
    }

    // ── AnalyticsPeriod bridge (the PeriodBounds SSOT seam) ─────────────

    /**
     * The rolling home-glance enum must map onto exactly "the current window of that granularity" —
     * this is what lets `PeriodBounds` compute both paths from one rule set without drift.
     */
    @Test fun `every AnalyticsPeriod maps to the current window of its granularity`() {
        assertEquals(
            AnalyticsWindows.current(WindowGranularity.DAY, wednesday),
            AnalyticsPeriod.TODAY.toWindow(wednesday),
        )
        assertEquals(
            AnalyticsWindows.current(WindowGranularity.WEEK, wednesday),
            AnalyticsPeriod.THIS_WEEK.toWindow(wednesday),
        )
        assertEquals(
            AnalyticsWindows.current(WindowGranularity.MONTH, wednesday),
            AnalyticsPeriod.THIS_MONTH.toWindow(wednesday),
        )
        assertEquals(AnalyticsWindows.LIFETIME, AnalyticsPeriod.LIFETIME.toWindow(wednesday))
    }

    /** No period may map to CUSTOM — it is a driver-drawn shape only, unreachable from the enum. */
    @Test fun `no rolling period maps to the CUSTOM granularity`() {
        AnalyticsPeriod.entries.forEach {
            assertTrue(it.name, it.toGranularity() != WindowGranularity.CUSTOM)
        }
    }
}
