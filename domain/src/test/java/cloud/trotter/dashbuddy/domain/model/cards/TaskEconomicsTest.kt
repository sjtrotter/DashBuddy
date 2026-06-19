package cloud.trotter.dashbuddy.domain.model.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit #6 — the task realized-rate FORMULAS + thresholds moved out of the bubble
 * composable into this pure, unit-testable SSOT.
 */
class TaskEconomicsTest {

    // ---- perMile (fixed; precomputed onto the snapshot) ----

    @Test
    fun `perMile divides net pay by distance`() {
        assertEquals(2.5, TaskEconomics.perMile(netPay = 10.0, distanceMiles = 4.0)!!, 1e-9)
    }

    @Test
    fun `perMile is null when an input is missing or distance is non-positive`() {
        assertNull(TaskEconomics.perMile(netPay = null, distanceMiles = 4.0))
        assertNull(TaskEconomics.perMile(netPay = 10.0, distanceMiles = null))
        assertNull(TaskEconomics.perMile(netPay = 10.0, distanceMiles = 0.0))
        assertNull(TaskEconomics.perMile(netPay = 10.0, distanceMiles = -1.0))
    }

    // ---- projectedHourly (live; erodes past the deadline) ----

    @Test
    fun `projectedHourly is pay over estimated hours before the deadline`() {
        // $12 over 30 estimated minutes = $24/hr, regardless of `now` while not yet overdue.
        val hourly = TaskEconomics.projectedHourly(
            netPay = 12.0, estMinutes = 30.0, deadlineMillis = 10_000L, now = 5_000L,
        )
        assertEquals(24.0, hourly!!, 1e-9)
    }

    @Test
    fun `projectedHourly holds steady up to the deadline then erodes past it`() {
        val deadline = 600_000L // 10 min in
        val atDeadline = TaskEconomics.projectedHourly(
            netPay = 12.0, estMinutes = 30.0, deadlineMillis = deadline, now = deadline,
        )!!
        // 10 minutes past the deadline stretches 30 -> 40 estimated minutes: $12 / (40/60) = $18/hr.
        val tenPast = TaskEconomics.projectedHourly(
            netPay = 12.0, estMinutes = 30.0, deadlineMillis = deadline, now = deadline + 600_000L,
        )!!
        assertEquals(24.0, atDeadline, 1e-9)
        assertEquals(18.0, tenPast, 1e-9)
        assertTrue("rate must erode past the deadline", tenPast < atDeadline)
    }

    @Test
    fun `projectedHourly with no deadline does not erode`() {
        val hourly = TaskEconomics.projectedHourly(
            netPay = 12.0, estMinutes = 30.0, deadlineMillis = null, now = 999_999_999L,
        )
        assertEquals(24.0, hourly!!, 1e-9)
    }

    @Test
    fun `projectedHourly is null when economics are unknown`() {
        assertNull(TaskEconomics.projectedHourly(null, 30.0, 0L, 0L))
        assertNull(TaskEconomics.projectedHourly(12.0, null, 0L, 0L))
        assertNull(TaskEconomics.projectedHourly(12.0, 0.0, 0L, 0L))
        assertNull(TaskEconomics.projectedHourly(12.0, -5.0, 0L, 0L))
    }

    // ---- drop floor + tier thresholds ----

    @Test
    fun `belowDropFloor tracks the floor boundary`() {
        assertTrue(TaskEconomics.belowDropFloor(TaskEconomics.DROP_FLOOR_HOURLY - 0.01))
        assertFalse(TaskEconomics.belowDropFloor(TaskEconomics.DROP_FLOOR_HOURLY))
        assertFalse(TaskEconomics.belowDropFloor(TaskEconomics.DROP_FLOOR_HOURLY + 5.0))
    }

    @Test
    fun `hourlyTier maps the good warn poor cutoffs`() {
        assertEquals(TaskEconomics.HourlyTier.GOOD, TaskEconomics.hourlyTier(TaskEconomics.GOOD_HOURLY))
        assertEquals(TaskEconomics.HourlyTier.GOOD, TaskEconomics.hourlyTier(25.0))
        assertEquals(TaskEconomics.HourlyTier.WARN, TaskEconomics.hourlyTier(TaskEconomics.WARN_HOURLY))
        assertEquals(TaskEconomics.HourlyTier.WARN, TaskEconomics.hourlyTier(TaskEconomics.GOOD_HOURLY - 0.01))
        assertEquals(TaskEconomics.HourlyTier.POOR, TaskEconomics.hourlyTier(TaskEconomics.WARN_HOURLY - 0.01))
        assertEquals(TaskEconomics.HourlyTier.POOR, TaskEconomics.hourlyTier(0.0))
    }
}
