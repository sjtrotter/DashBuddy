package cloud.trotter.log.gig.domain.census.contributor

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** #1625: one recorded shift as eligibility reads it: its start, and its end (or its last activity while open). */
data class ShiftSpan(val startedAtMillis: Long, val lastAtMillis: Long)

/**
 * #1625: THE contributor eligibility rule (ADR-0011 §15), computed on the phone from data it already has. Only the
 * answer ever leaves the phone, as `{"eligible":true}` and only when it is true; the counts never do.
 *
 * Recognition is not an input, by design: the phones the census needs most are the ones whose screens gig.log fails
 * to recognize, so no recognition ratio can raise or lower eligibility.
 */
object ContributorEligibility {
    /**
     * The census ID enrolled at least this many UTC calendar days ago: the server's own quarantine (census
     * `VocabularyEligibility`: the enrolment's UTC day plus 8 is today or earlier), so the phone never invites an install
     * the server would refuse. A `403 install_too_new` still covers a skewed phone clock.
     */
    const val MIN_ENROLLED_UTC_DAYS = 8

    /** The activity window: today and the days before it, local calendar days. */
    const val ACTIVE_WINDOW_DAYS = 7

    /** Days in the window on which a recorded shift ran. */
    const val MIN_ACTIVE_DAYS = 3

    /** Ended shifts gig.log recorded, all time. */
    const val MIN_ENDED_SHIFTS = 2

    data class Inputs(
        val censusOn: Boolean,
        /** When this phone's census ID enrolled; null while it has none. */
        val enrolledAtMillis: Long?,
        /** Shifts that ran in the window ([windowStartMillis] onward). */
        val shifts: List<ShiftSpan>,
        val endedShifts: Int,
    )

    fun eligible(inputs: Inputs, nowMillis: Long, zone: ZoneId): Boolean {
        val enrolledAt = inputs.enrolledAtMillis ?: return false
        return inputs.censusOn &&
            !day(enrolledAt, ZoneOffset.UTC).plusDays(MIN_ENROLLED_UTC_DAYS.toLong()).isAfter(day(nowMillis, ZoneOffset.UTC)) &&
            activeDays(inputs.shifts, nowMillis, zone) >= MIN_ACTIVE_DAYS &&
            inputs.endedShifts >= MIN_ENDED_SHIFTS
    }

    /** The days among today and the [ACTIVE_WINDOW_DAYS] - 1 before it, in [zone], on which a shift ran. */
    fun activeDays(shifts: List<ShiftSpan>, nowMillis: Long, zone: ZoneId): Int {
        val today = day(nowMillis, zone)
        val first = today.minusDays(ACTIVE_WINDOW_DAYS - 1L)
        val days = HashSet<LocalDate>()
        for (shift in shifts) {
            var day = maxOf(day(shift.startedAtMillis, zone), first)
            val last = minOf(day(maxOf(shift.startedAtMillis, shift.lastAtMillis), zone), today)
            while (!day.isAfter(last)) {
                days += day
                day = day.plusDays(1)
            }
        }
        return days.size
    }

    /** The start of the window's first day in [zone]: a shift that last ran before this cannot count. */
    fun windowStartMillis(nowMillis: Long, zone: ZoneId): Long =
        day(nowMillis, zone).minusDays(ACTIVE_WINDOW_DAYS - 1L).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
