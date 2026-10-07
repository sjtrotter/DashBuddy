package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity.CUSTOM
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity.DAY
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity.LIFETIME
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity.MONTH
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity.WEEK
import org.junit.Assert.assertEquals
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
    private val wednesday = LocalDate.of(2026, 7, 15)
    private fun date(value: String): LocalDate = LocalDate.parse(value)
    private fun custom(start: String, end: String) = AnalyticsWindows.custom(date(start), date(end))
    private fun current(granularity: WindowGranularity, today: LocalDate = wednesday) =
        AnalyticsWindows.current(granularity, today)

    @Test fun `current window bounds`() {
        data class Case(val name: String, val granularity: WindowGranularity, val today: LocalDate,
                        val start: String?, val end: String?, val days: Long?)
        val cases = listOf(
            Case("day", DAY, wednesday, "2026-07-15", "2026-07-15", 1L),
            Case("Sunday stays in the preceding Monday week", WEEK, date("2026-07-19"), "2026-07-13", "2026-07-19", 7L),
            Case("month", MONTH, wednesday, "2026-07-01", "2026-07-31", 31L),
            Case("lifetime", LIFETIME, wednesday, null, null, null),
        ) + (0L..6L).map { offset ->
            Case("Monday week offset=$offset", WEEK, date("2026-07-13").plusDays(offset), "2026-07-13", "2026-07-19", 7L)
        }
        cases.forEach { (name, granularity, today, start, end, days) ->
            val window = current(granularity, today)
            assertEquals("$name start", start?.let(::date), window.startDate)
            assertEquals("$name end", end?.let(::date), window.endDateInclusive)
            assertEquals("$name length", days, window.lengthDays)
            if (granularity == WEEK) assertEquals(name, DayOfWeek.MONDAY, window.startDate!!.dayOfWeek)
            if (granularity == LIFETIME) {
                assertTrue(name, window.isLifetime)
                assertNull(name, window.endDateExclusive)
                assertTrue("$name contains 1999", window.contains(date("1999-01-01")))
            }
        }
    }

    @Test fun `step bounds and identity cases`() {
        data class Case(val name: String, val window: AnalyticsWindow, val steps: Int,
                        val start: String?, val end: String?, val days: Long?)
        listOf(
            Case("week back", current(WEEK), -1, "2026-07-06", "2026-07-12", 7L),
            Case("day back", current(DAY), -1, "2026-07-14", "2026-07-14", 1L),
            Case("month back", current(MONTH), -1, "2026-06-01", "2026-06-30", 30L),
            Case("January back", current(MONTH, date("2026-01-10")), -1, "2025-12-01", "2025-12-31", 31L),
            Case("custom length", custom("2026-07-10", "2026-07-14"), -1, "2026-07-05", "2026-07-09", 5L),
            Case("lifetime back", AnalyticsWindows.LIFETIME, -3, null, null, null),
            Case("lifetime forward", AnalyticsWindows.LIFETIME, 3, null, null, null),
            Case("zero identity", current(WEEK), 0, "2026-07-13", "2026-07-19", 7L),
        ).forEach { (name, window, steps, start, end, days) ->
            val stepped = AnalyticsWindows.step(window, steps)
            assertEquals("$name start", start?.let(::date), stepped.startDate)
            assertEquals("$name end", end?.let(::date), stepped.endDateInclusive)
            assertEquals("$name length", days, stepped.lengthDays)
            assertEquals("$name granularity", window.granularity, stepped.granularity)
            if (window.isLifetime || steps == 0) assertEquals(name, window, stepped)
        }
    }

    @Test fun `stepping back then forward round-trips for every granularity`() {
        val cases = listOf(DAY, WEEK, MONTH).flatMap { granularity -> (1..14).map { granularity to it } }
        cases.forEach { (granularity, steps) ->
            val window = current(granularity)
            assertEquals("$granularity step $steps", window,
                AnalyticsWindows.step(AnalyticsWindows.step(window, -steps), steps))
        }
    }

    @Test fun `previous window calendar bounds`() {
        data class Case(val name: String, val window: AnalyticsWindow, val start: String?, val end: String?, val days: Long?)
        listOf(
            Case("week", current(WEEK), "2026-07-06", "2026-07-12", 7L),
            Case("March", current(MONTH, date("2026-03-15")), "2026-02-01", "2026-02-28", 28L),
            Case("leap March", current(MONTH, date("2028-03-15")), "2028-02-01", "2028-02-29", 29L),
            Case("January", current(MONTH, date("2026-01-31")), "2025-12-01", "2025-12-31", 31L),
            Case("day", current(DAY), "2026-07-14", "2026-07-14", 1L),
            Case("custom", custom("2026-07-10", "2026-07-16"), "2026-07-03", "2026-07-09", 7L),
            Case("lifetime has no comparison", AnalyticsWindows.LIFETIME, null, null, null),
        ).forEach { (name, window, start, end, days) ->
            val previous = AnalyticsWindows.previous(window)
            if (start == null) {
                assertNull(name, previous)
            } else {
                requireNotNull(previous) { name }
                assertEquals("$name start", date(start), previous.startDate)
                assertEquals("$name end", end?.let(::date), previous.endDateInclusive)
                assertEquals("$name length", days, previous.lengthDays)
                if (window.granularity == CUSTOM) assertEquals(name, window.lengthDays, previous.lengthDays)
            }
        }
    }

    @Test fun `previous windows abut without gaps or overlaps`() {
        (listOf(DAY, WEEK, MONTH).map { current(it) } + custom("2026-07-10", "2026-07-16"))
            .forEach { window ->
                assertEquals("$window", window.startDate, AnalyticsWindows.previous(window)!!.endDateExclusive)
            }
    }

    @Test fun `paging fences`() {
        data class Case(val name: String, val window: AnalyticsWindow, val forward: Boolean, val back: Boolean)
        val cases = listOf(DAY, WEEK, MONTH).flatMap { granularity ->
            val window = current(granularity)
            listOf(Case("current $granularity", window, false, true),
                Case("previous $granularity", AnalyticsWindows.step(window, -1), true, true))
        } + listOf(
            Case("custom reaches today", AnalyticsWindows.custom(wednesday.minusDays(3), wednesday), false, true),
            Case("custom ends yesterday", AnalyticsWindows.custom(wednesday.minusDays(3), wednesday.minusDays(1)), true, true),
            Case("lifetime", AnalyticsWindows.LIFETIME, false, false),
            Case("single day custom", AnalyticsWindows.custom(wednesday, wednesday), false, true),
        )
        cases.forEach { (name, window, forward, back) ->
            assertEquals("$name forward", forward, AnalyticsWindows.canStepForward(window, wednesday))
            assertEquals("$name back", back, AnalyticsWindows.canStepBack(window))
        }
    }

    @Test fun `custom ranges include both endpoints`() {
        val window = custom("2026-07-10", "2026-07-12")
        listOf("2026-07-10" to true, "2026-07-12" to true, "2026-07-13" to false, "2026-07-09" to false)
            .forEach { (day, expected) -> assertEquals(day, expected, window.contains(date(day))) }
        listOf("three day range" to (window to 3L),
            "single day range" to (AnalyticsWindows.custom(wednesday, wednesday) to 1L))
            .forEach { (name, case) -> assertEquals(name, case.second, case.first.lengthDays) }
    }

    @Test fun `invalid windows and selections are rejected`() {
        data class Case(val name: String, val exception: Class<out Throwable>, val create: () -> Any)
        listOf(
            Case("CUSTOM has no current window", IllegalStateException::class.java) { current(CUSTOM) },
            Case("backwards range", IllegalArgumentException::class.java) { custom("2026-07-12", "2026-07-10") },
            Case("empty window", IllegalArgumentException::class.java) { AnalyticsWindow(CUSTOM, wednesday, wednesday) },
            Case("relative CUSTOM", IllegalArgumentException::class.java) { AnalyticsWindowSelection.Relative(CUSTOM, 0) },
            Case("backwards selection", IllegalArgumentException::class.java) {
                AnalyticsWindowSelection.Custom(date("2026-07-12"), date("2026-07-10"))
            },
        ).forEach { (name, exception, create) -> assertThrows(name, exception) { create() } }
    }

    @Test fun `selections resolve relatively or preserve absolute bounds`() {
        data class Case(val name: String, val selection: AnalyticsWindowSelection, val today: LocalDate,
                        val expected: AnalyticsWindow)
        val relative = AnalyticsWindowSelection.Relative(WEEK, 0)
        val absolute = AnalyticsWindowSelection.Custom(date("2026-03-02"), date("2026-03-08"))
        listOf(
            Case("default pay week", AnalyticsWindowSelection.DEFAULT, wednesday, current(WEEK)),
            Case("relative this week", relative, wednesday, AnalyticsWindow(WEEK, date("2026-07-13"), date("2026-07-20"))),
            Case("relative reopened next week", relative, wednesday.plusWeeks(1), AnalyticsWindow(WEEK, date("2026-07-20"), date("2026-07-27"))),
            Case("relative two weeks back", AnalyticsWindowSelection.Relative(WEEK, -2), wednesday, AnalyticsWindow(WEEK, date("2026-06-29"), date("2026-07-06"))),
            Case("absolute in July", absolute, wednesday, AnalyticsWindow(CUSTOM, date("2026-03-02"), date("2026-03-09"))),
            Case("absolute next year", absolute, date("2027-01-01"), AnalyticsWindow(CUSTOM, date("2026-03-02"), date("2026-03-09"))),
        ).forEach { (name, selection, today, expected) ->
            val resolved = selection.resolve(today)
            assertEquals(name, expected, resolved)
            if (selection is AnalyticsWindowSelection.Custom) {
                assertEquals("$name start", selection.startDate, resolved.startDate)
                assertEquals("$name inclusive end", selection.endDateInclusive, resolved.endDateInclusive)
            }
        }
    }

    @Test fun `rolling periods bridge to current windows and never CUSTOM`() {
        listOf(AnalyticsPeriod.TODAY to DAY, AnalyticsPeriod.THIS_WEEK to WEEK,
            AnalyticsPeriod.THIS_MONTH to MONTH, AnalyticsPeriod.LIFETIME to LIFETIME)
            .forEach { (period, granularity) ->
                assertEquals(period.name, current(granularity), period.toWindow(wednesday))
            }
        AnalyticsPeriod.entries.forEach { period -> assertTrue(period.name, period.toGranularity() != CUSTOM) }
    }
}
