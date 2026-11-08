package cloud.trotter.log.gig.domain.analytics.recap

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One shift with the local date it started on: the Shifts list's row. */
data class DatedShift(val date: LocalDate, val shift: DayShift)

/** One Monday-to-Sunday week of the Shifts list: its Monday and its shifts, newest first. */
data class ShiftWeek(val start: LocalDate, val shifts: List<DatedShift>)

/**
 * The pure owner of the Shifts list's grouping (#1515): every shift under the Monday-to-Sunday week of the local day
 * it started (a shift lands wholly on its start day, as Recap's days do), newest week first and newest shift first.
 * The target week is always present: with no shift it is inserted empty, in its place. No other week is empty.
 */
object ShiftWeeks {

    /** The Monday of [date]'s week. */
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** [shifts] in any order, grouped against [zone]; [target] is any date in the week the list opens on. */
    fun of(shifts: List<DayShift>, target: LocalDate, zone: ZoneId): List<ShiftWeek> {
        val targetStart = weekStart(target)
        val byWeek = shifts
            .sortedByDescending { it.startedAt }
            .map { DatedShift(Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate(), it) }
            .groupBy { weekStart(it.date) }
        val starts = (byWeek.keys + targetStart).sortedDescending()
        return starts.map { start -> ShiftWeek(start, byWeek[start].orEmpty()) }
    }
}
