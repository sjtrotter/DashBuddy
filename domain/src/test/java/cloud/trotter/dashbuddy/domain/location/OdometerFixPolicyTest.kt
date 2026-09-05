package cloud.trotter.dashbuddy.domain.location

import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1057 / #918 — the odometer's per-fix admission gate.
 *
 * Every fixture is expressed as "N meters due north of the origin" so the Haversine distance the
 * policy computes is exactly the number the test names.
 */
class OdometerFixPolicyTest {

    /** Degrees of latitude for [meters] north, using the same earth radius as `Coordinates.distanceTo`. */
    private fun degreesNorth(meters: Double): Double = Math.toDegrees(meters / 6_371_000.0)

    private fun fix(
        metersNorth: Double,
        accuracyMeters: Double? = null,
        timestampMs: Long? = null,
    ) = Coordinates(
        latitude = degreesNorth(metersNorth),
        longitude = 0.0,
        accuracyMeters = accuracyMeters,
        timestampMs = timestampMs,
    )

    @Test
    fun `the first fix of a tracking run becomes the reference and accrues nothing`() {
        val verdict = OdometerFixPolicy.judge(null, fix(0.0, accuracyMeters = 8.0, timestampMs = 1_000L))
        assertEquals(OdometerFixPolicy.Verdict.Reference, verdict)
    }

    @Test
    fun `the fielded 905-mile teleport is rejected for implausible speed`() {
        // 2026-09-03: one fused fix ~1,457 km away added 905.37 mi in 18.4 min (~1,320 m per second).
        val last = fix(0.0, accuracyMeters = 10.0, timestampMs = 0L)
        val next = fix(1_457_000.0, accuracyMeters = 10.0, timestampMs = 1_104_000L) // 18.4 min

        val verdict = OdometerFixPolicy.judge(last, next)

        assertTrue("teleport rejected", verdict is OdometerFixPolicy.Verdict.Reject)
        verdict as OdometerFixPolicy.Verdict.Reject
        assertEquals(OdometerFixPolicy.Reason.IMPLAUSIBLE_SPEED, verdict.reason)
        assertEquals(1_457_000.0, verdict.deltaMeters, 1_000.0)
        assertEquals(1_104_000L, verdict.dtMillis)
        assertTrue("implied speed reported", (verdict.impliedMps ?: 0.0) > 1_000.0)
    }

    @Test
    fun `a highway leg of 150 m in 5 s is accepted`() {
        val last = fix(0.0, accuracyMeters = 6.0, timestampMs = 0L)
        val next = fix(150.0, accuracyMeters = 6.0, timestampMs = 5_000L) // 30 m per second

        val verdict = OdometerFixPolicy.judge(last, next)

        assertTrue("real motion accepted", verdict is OdometerFixPolicy.Verdict.Accept)
        assertEquals(150.0, (verdict as OdometerFixPolicy.Verdict.Accept).deltaMeters, 0.5)
    }

    @Test
    fun `jitter of 12 m inside a 25 m error radius is ignored and the reference does not move`() {
        val reference = fix(0.0, accuracyMeters = 25.0, timestampMs = 0L)
        val bounce = fix(12.0, accuracyMeters = 25.0, timestampMs = 3_000L)

        val verdict = OdometerFixPolicy.judge(reference, bounce)

        assertTrue("#918 jitter ignored", verdict is OdometerFixPolicy.Verdict.Ignore)
        verdict as OdometerFixPolicy.Verdict.Ignore
        assertEquals(12.0, verdict.deltaMeters, 0.5)
        assertEquals("floor widens to the fixes' own accuracy", 25.0, verdict.floorMeters, 1e-9)

        // The caller keeps `reference` on Ignore — proven by the next assertion measuring from it again.
        val stillIgnored = OdometerFixPolicy.judge(reference, fix(11.0, accuracyMeters = 25.0, timestampMs = 6_000L))
        assertTrue("a second bounce is still jitter", stillIgnored is OdometerFixPolicy.Verdict.Ignore)
    }

    @Test
    fun `slow creep accumulates because the reference does not move on Ignore — 2 m steps land as one 6 m Accept`() {
        // The property this whole design turns on: each step is measured from the LAST ACCEPTED fix,
        // not from the previous fix, so a drive-through line inching forward is not silently discarded.
        val reference = fix(0.0, accuracyMeters = 3.0, timestampMs = 0L) // floor = MIN_DELTA_METERS = 5 m
        val steps = listOf(
            fix(2.0, accuracyMeters = 3.0, timestampMs = 3_000L),
            fix(4.0, accuracyMeters = 3.0, timestampMs = 6_000L),
            fix(6.0, accuracyMeters = 3.0, timestampMs = 9_000L),
        )

        val verdicts = steps.map { OdometerFixPolicy.judge(reference, it) } // reference never moves

        assertTrue("2 m ignored", verdicts[0] is OdometerFixPolicy.Verdict.Ignore)
        assertTrue("4 m still under the 5 m floor", verdicts[1] is OdometerFixPolicy.Verdict.Ignore)
        assertTrue("6 m clears the floor", verdicts[2] is OdometerFixPolicy.Verdict.Accept)
        assertEquals(
            "the WHOLE displacement from the reference is accrued, not just the last step",
            6.0,
            (verdicts[2] as OdometerFixPolicy.Verdict.Accept).deltaMeters,
            0.1,
        )
    }

    @Test
    fun `a fix with 80 m accuracy is rejected outright and is never even a reference`() {
        val poor = fix(0.0, accuracyMeters = 80.0, timestampMs = 1_000L)

        val fromNothing = OdometerFixPolicy.judge(null, poor)
        assertTrue("a poor fix cannot seed the reference", fromNothing is OdometerFixPolicy.Verdict.Reject)
        assertEquals(
            OdometerFixPolicy.Reason.POOR_ACCURACY,
            (fromNothing as OdometerFixPolicy.Verdict.Reject).reason,
        )

        val fromReference = OdometerFixPolicy.judge(fix(0.0, accuracyMeters = 5.0, timestampMs = 0L), poor)
        assertTrue(fromReference is OdometerFixPolicy.Verdict.Reject)
        assertEquals(
            OdometerFixPolicy.Reason.POOR_ACCURACY,
            (fromReference as OdometerFixPolicy.Verdict.Reject).reason,
        )
        assertEquals(80.0, fromReference.accuracyMeters!!, 1e-9)
    }

    @Test
    fun `with no timestamps a 2500 m jump is rejected but a 300 m displacement is accepted`() {
        val last = fix(0.0, accuracyMeters = 10.0)

        val jump = OdometerFixPolicy.judge(last, fix(2_500.0, accuracyMeters = 10.0))
        assertTrue(jump is OdometerFixPolicy.Verdict.Reject)
        assertEquals(
            OdometerFixPolicy.Reason.IMPLAUSIBLE_JUMP,
            (jump as OdometerFixPolicy.Verdict.Reject).reason,
        )
        assertTrue("no elapsed time to report", jump.dtMillis == null)
        assertTrue("no implied speed to report", jump.impliedMps == null)

        val ordinary = OdometerFixPolicy.judge(last, fix(300.0, accuracyMeters = 10.0))
        assertTrue(ordinary is OdometerFixPolicy.Verdict.Accept)
        assertEquals(300.0, (ordinary as OdometerFixPolicy.Verdict.Accept).deltaMeters, 0.5)
    }

    @Test
    fun `a fix that is not newer than the reference is rejected as non-monotonic`() {
        val last = fix(0.0, accuracyMeters = 5.0, timestampMs = 10_000L)

        val sameInstant = OdometerFixPolicy.judge(last, fix(150.0, accuracyMeters = 5.0, timestampMs = 10_000L))
        assertTrue(sameInstant is OdometerFixPolicy.Verdict.Reject)
        assertEquals(
            OdometerFixPolicy.Reason.NON_MONOTONIC_TIME,
            (sameInstant as OdometerFixPolicy.Verdict.Reject).reason,
        )

        val goingBackwards = OdometerFixPolicy.judge(last, fix(150.0, accuracyMeters = 5.0, timestampMs = 9_000L))
        assertTrue(goingBackwards is OdometerFixPolicy.Verdict.Reject)
        assertEquals(
            OdometerFixPolicy.Reason.NON_MONOTONIC_TIME,
            (goingBackwards as OdometerFixPolicy.Verdict.Reject).reason,
        )
    }

    @Test
    fun `an accuracy-less fix still gets the 5 m floor and the speed check`() {
        val last = fix(0.0, timestampMs = 0L)

        assertTrue(
            "4 m is under the kept MIN_DELTA_METERS floor",
            OdometerFixPolicy.judge(last, fix(4.0, timestampMs = 3_000L)) is OdometerFixPolicy.Verdict.Ignore,
        )
        val fast = OdometerFixPolicy.judge(last, fix(1_000.0, timestampMs = 1_000L)) // 1,000 m/s
        assertTrue(fast is OdometerFixPolicy.Verdict.Reject)
        assertEquals(
            OdometerFixPolicy.Reason.IMPLAUSIBLE_SPEED,
            (fast as OdometerFixPolicy.Verdict.Reject).reason,
        )
    }
}
