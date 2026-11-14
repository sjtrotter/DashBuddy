package cloud.trotter.log.gig.domain.census.contributor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** #1625: the eligibility rule at each threshold, one input below it at a time. */
class ContributorEligibilityTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = ZonedDateTime.of(2026, 11, 13, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val day = 86_400_000L

    /** A two-hour shift starting at noon, [daysAgo] local days before today. */
    private fun shift(daysAgo: Long): ShiftSpan {
        val start = ZonedDateTime.of(2026, 11, 13, 12, 0, 0, 0, zone).minusDays(daysAgo).toInstant().toEpochMilli()
        return ShiftSpan(start, start + 2 * 3_600_000L)
    }

    private fun inputs(
        censusOn: Boolean = true,
        enrolledDaysAgo: Long? = 8,
        activeDays: Int = 3,
        endedShifts: Int = 2,
    ) = ContributorEligibility.Inputs(
        censusOn = censusOn,
        enrolledAtMillis = enrolledDaysAgo?.let { now - it * day },
        shifts = (0 until activeDays).map { shift(it.toLong() * 2) },
        endedShifts = endedShifts,
    )

    private fun eligible(inputs: ContributorEligibility.Inputs) = ContributorEligibility.eligible(inputs, now, zone)

    @Test fun `every threshold met is eligible`() = assertTrue(eligible(inputs()))

    @Test fun `census enrolled 7 UTC days ago is not, 8 is, as the server counts`() {
        assertFalse(eligible(inputs(enrolledDaysAgo = 7)))
        assertTrue(eligible(inputs(enrolledDaysAgo = 8)))
        // UTC calendar days, not 24-hour spans: 23:59 on the 5th to 00:01 on the 13th is 8 days; 00:01 on the 6th is 7.
        val at = { d: Int, h: Int, m: Int -> ZonedDateTime.of(2026, 11, d, h, m, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli() }
        val justAfter = at(13, 0, 1)
        val active = (0..2).map { ShiftSpan(justAfter - it * day - 60_000L, justAfter - it * day) }
        val base = ContributorEligibility.Inputs(true, null, active, endedShifts = 2)
        assertTrue(ContributorEligibility.eligible(base.copy(enrolledAtMillis = at(5, 23, 59)), justAfter, ZoneOffset.UTC))
        assertFalse(ContributorEligibility.eligible(base.copy(enrolledAtMillis = at(6, 0, 1)), justAfter, ZoneOffset.UTC))
    }

    @Test fun `active on 2 of the last 7 days is not, 3 is`() {
        assertFalse(eligible(inputs(activeDays = 2)))
        assertTrue(eligible(inputs(activeDays = 3)))
    }

    @Test fun `1 ended shift is not, 2 are`() {
        assertFalse(eligible(inputs(endedShifts = 1)))
        assertTrue(eligible(inputs(endedShifts = 2)))
    }

    @Test fun `census off is never eligible`() {
        assertFalse(eligible(inputs(censusOn = false)))
    }

    @Test fun `no census ID is never eligible`() {
        assertFalse(eligible(inputs(enrolledDaysAgo = null)))
    }

    @Test fun `a shift 7 days ago is outside the window, 6 days ago is inside`() {
        assertEquals(1, ContributorEligibility.activeDays(listOf(shift(6)), now, zone))
        assertEquals(0, ContributorEligibility.activeDays(listOf(shift(7)), now, zone))
        assertTrue(ContributorEligibility.windowStartMillis(now, zone) <= shift(6).startedAtMillis)
        assertTrue(ContributorEligibility.windowStartMillis(now, zone) > shift(7).lastAtMillis)
    }

    @Test fun `two shifts on one day are one day, a shift past midnight is two`() {
        assertEquals(1, ContributorEligibility.activeDays(listOf(shift(1), shift(1)), now, zone))
        val late = ZonedDateTime.of(2026, 11, 11, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(2, ContributorEligibility.activeDays(listOf(ShiftSpan(late, late + 4 * 3_600_000L)), now, zone))
    }

    @Test fun `days are local calendar days`() {
        // 23:30 on the 12th in Chicago is the 13th in UTC: one local day, the 12th.
        val evening = ZonedDateTime.of(2026, 11, 12, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val shifts = listOf(ShiftSpan(evening, evening + 10 * 60_000L), shift(1))
        assertEquals(1, ContributorEligibility.activeDays(shifts, now, zone))
    }
}
