package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #970 — the delta rule behind every "vs the previous window" chip (brief §3.3 + the §9 honesty
 * bar): the analytics recap hero, and — since #977 — Home's "This week" block. Pure, so the
 * comparison rules are proved without rendering.
 *
 * Each rule exists to stop a specific dishonest reading:
 *  - no previous window (Lifetime) must say so, not print `▲ 0%`;
 *  - a previous window that earned nothing can't be a percentage denominator;
 *  - a sub-half-percent wobble is noise, and rendering it as a trend would invite the driver to act
 *    on it.
 */
class NetDeltaTest {

    @Test fun `a null previous window yields NONE`() {
        assertEquals(NetDelta.Direction.NONE, NetDelta.delta(300.0, null).direction)
        assertNull(NetDelta.delta(300.0, null).fraction)
    }

    @Test fun `a real increase yields UP with the signed fraction`() {
        val delta = NetDelta.delta(currentNet = 300.0, previousNet = 200.0)
        assertEquals(NetDelta.Direction.UP, delta.direction)
        assertEquals(0.5, delta.fraction!!, 1e-9)
    }

    @Test fun `a real decrease yields DOWN with a negative fraction`() {
        val delta = NetDelta.delta(currentNet = 150.0, previousNet = 200.0)
        assertEquals(NetDelta.Direction.DOWN, delta.direction)
        assertEquals(-0.25, delta.fraction!!, 1e-9)
    }

    @Test fun `an identical window is FLAT with no fraction`() {
        val delta = NetDelta.delta(currentNet = 200.0, previousNet = 200.0)
        assertEquals(NetDelta.Direction.FLAT, delta.direction)
        assertNull(delta.fraction)
    }

    /** A sub-half-percent move is noise — reporting it as a trend would be a false signal. */
    @Test fun `a sub-half-percent move reads as FLAT`() {
        assertEquals(NetDelta.Direction.FLAT, NetDelta.delta(200.4, 200.0).direction)
        assertEquals(NetDelta.Direction.FLAT, NetDelta.delta(199.6, 200.0).direction)
        // …but just past the threshold it becomes a real direction.
        assertEquals(NetDelta.Direction.UP, NetDelta.delta(202.0, 200.0).direction)
        assertEquals(NetDelta.Direction.DOWN, NetDelta.delta(198.0, 200.0).direction)
    }

    /** Dividing by a zero previous window is the divide-by-zero the worded arm exists to avoid. */
    @Test fun `earning after an empty previous window is FROM_ZERO, never a percentage`() {
        val delta = NetDelta.delta(currentNet = 120.0, previousNet = 0.0)
        assertEquals(NetDelta.Direction.FROM_ZERO, delta.direction)
        assertNull(delta.fraction)
    }

    @Test fun `two empty windows in a row are FLAT`() {
        assertEquals(NetDelta.Direction.FLAT, NetDelta.delta(0.0, 0.0).direction)
    }

    /** Sub-cent noise on either side is still "nothing", not a comparison. */
    @Test fun `sub-cent amounts count as zero on both sides`() {
        assertEquals(NetDelta.Direction.FLAT, NetDelta.delta(0.001, 0.001).direction)
        assertEquals(NetDelta.Direction.FROM_ZERO, NetDelta.delta(50.0, 0.001).direction)
    }

    /** A negative previous net still compares — the magnitude is the denominator, so the sign is honest. */
    @Test fun `recovering from a losing window reads as UP`() {
        val delta = NetDelta.delta(currentNet = 5.0, previousNet = -10.0)
        assertEquals(NetDelta.Direction.UP, delta.direction)
        assertEquals(1.5, delta.fraction!!, 1e-9)
    }

    @Test fun `a losing window after a profitable one reads as DOWN`() {
        val delta = NetDelta.delta(currentNet = -20.0, previousNet = 100.0)
        assertEquals(NetDelta.Direction.DOWN, delta.direction)
        assertEquals(-1.2, delta.fraction!!, 1e-9)
    }

    // ── isEmpty: "nothing recorded" vs "recorded zeros" ─────────────────

    @Test fun `a window with no records at all is empty`() {
        assertTrue(NetDelta.isEmpty(PeriodEconomics.EMPTY))
    }

    @Test fun `a window with deliveries is not empty even at zero gross`() {
        val economics = PeriodEconomics.EMPTY.copy(
            totals = PeriodTotals(earnings = 0.0, miles = 0.0, deliveries = 1, jobs = 1, onlineDuration = 0L),
        )
        assertFalse(NetDelta.isEmpty(economics))
    }

    /** Time online with nothing earned is a real (and worth stating) outcome, not an empty window. */
    @Test fun `a window with online time but no earnings is not empty`() {
        val economics = PeriodEconomics.EMPTY.copy(
            totals = PeriodTotals(earnings = 0.0, miles = 0.0, deliveries = 0, jobs = 0, onlineDuration = 3_600_000L),
        )
        assertFalse(NetDelta.isEmpty(economics))
    }

    @Test fun `a window with gross is not empty`() {
        assertFalse(NetDelta.isEmpty(PeriodEconomics.EMPTY.copy(grossEarnings = 12.0)))
    }
}
