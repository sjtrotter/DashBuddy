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
        monotonicMs: Long? = null,
    ) = Coordinates(
        latitude = degreesNorth(metersNorth),
        longitude = 0.0,
        accuracyMeters = accuracyMeters,
        timestampMs = timestampMs,
        monotonicMs = monotonicMs,
    )

    private fun reasonOf(verdict: OdometerFixPolicy.Verdict): OdometerFixPolicy.Reason {
        assertTrue("expected a Reject, got $verdict", verdict is OdometerFixPolicy.Verdict.Reject)
        return (verdict as OdometerFixPolicy.Verdict.Reject).reason
    }

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

    // ---------------------------------------------------------------------------------------------
    // #1057 round 2 — R1: a malformed fix is not a measurement, and must never reach the total.
    // Every bound in the policy is a comparison, and every comparison against NaN is false, so
    // without an explicit well-formedness gate a NaN falls THROUGH to `Accept(NaN)`.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a non-finite latitude is rejected as an invalid fix, from a reference and from nothing`() {
        val nan = Coordinates(latitude = Double.NaN, longitude = 0.0, accuracyMeters = 8.0, timestampMs = 5_000L)

        assertEquals(
            "a NaN fix cannot seed the reference",
            OdometerFixPolicy.Reason.INVALID_FIX,
            reasonOf(OdometerFixPolicy.judge(null, nan)),
        )
        assertEquals(
            OdometerFixPolicy.Reason.INVALID_FIX,
            reasonOf(OdometerFixPolicy.judge(fix(0.0, accuracyMeters = 8.0, timestampMs = 0L), nan)),
        )
    }

    @Test
    fun `infinite and out-of-range latitude and longitude are invalid fixes`() {
        val reference = fix(0.0, accuracyMeters = 8.0, timestampMs = 0L)
        val malformed = listOf(
            Coordinates(latitude = Double.POSITIVE_INFINITY, longitude = 0.0, timestampMs = 5_000L),
            Coordinates(latitude = 0.0, longitude = Double.NaN, timestampMs = 5_000L),
            Coordinates(latitude = 91.0, longitude = 0.0, timestampMs = 5_000L),
            Coordinates(latitude = -90.5, longitude = 0.0, timestampMs = 5_000L),
            Coordinates(latitude = 0.0, longitude = 180.5, timestampMs = 5_000L),
            Coordinates(latitude = 0.0, longitude = 0.0, timestampMs = -1L),
            Coordinates(latitude = 0.0, longitude = 0.0, monotonicMs = -1L),
        )

        for (fix in malformed) {
            assertEquals(
                "malformed fix rejected",
                OdometerFixPolicy.Reason.INVALID_FIX,
                reasonOf(OdometerFixPolicy.judge(reference, fix)),
            )
        }
    }

    @Test
    fun `a NaN or negative accuracy is an invalid fix, not a poor-accuracy one`() {
        val reference = fix(0.0, accuracyMeters = 8.0, timestampMs = 0L)

        // A NaN accuracy is the nastiest of the family: it makes the jitter FLOOR NaN, so
        // `delta <= floor` is false and a 1 m stationary bounce is ACCEPTED as motion.
        assertEquals(
            OdometerFixPolicy.Reason.INVALID_FIX,
            reasonOf(OdometerFixPolicy.judge(reference, fix(1.0, accuracyMeters = Double.NaN, timestampMs = 3_000L))),
        )
        assertEquals(
            OdometerFixPolicy.Reason.INVALID_FIX,
            reasonOf(OdometerFixPolicy.judge(reference, fix(150.0, accuracyMeters = -1.0, timestampMs = 5_000L))),
        )
        assertEquals(
            OdometerFixPolicy.Reason.INVALID_FIX,
            reasonOf(
                OdometerFixPolicy.judge(
                    reference,
                    fix(150.0, accuracyMeters = Double.POSITIVE_INFINITY, timestampMs = 5_000L),
                )
            ),
        )
    }

    @Test
    fun `a well-formed near-antipodal pair produces a finite delta and is rejected as a jump, never accepted`() {
        // The other half of the NaN family: valid coordinates whose haversine intermediate rounds
        // past 1.0. The clamp in `Coordinates.distanceTo` keeps the delta finite, and the ordinary
        // bounds then do their job.
        val here = Coordinates(latitude = 45.0, longitude = 10.0, accuracyMeters = 8.0)
        val antipode = Coordinates(latitude = -45.0, longitude = -170.0, accuracyMeters = 8.0)

        val verdict = OdometerFixPolicy.judge(here, antipode)

        assertEquals(OdometerFixPolicy.Reason.IMPLAUSIBLE_JUMP, reasonOf(verdict))
        assertTrue(
            "the reported delta is finite",
            (verdict as OdometerFixPolicy.Verdict.Reject).deltaMeters.isFinite(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // #1057 round 2 — R2: elapsed time comes from the MONOTONIC clock wherever both fixes carry one.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a wall-clock step-back is accepted when the monotonic clock advances normally`() {
        // An NTP correction steps `Location.time` 60 s backwards mid-drive. Judged on wall time this
        // is NON_MONOTONIC_TIME, and every fix after it fails too until the clock catches up —
        // ~110 s of silently lost mileage. On the monotonic clock it is an ordinary 5 s / 150 m leg.
        val last = fix(0.0, accuracyMeters = 6.0, timestampMs = 1_000_000L, monotonicMs = 500_000L)
        val next = fix(150.0, accuracyMeters = 6.0, timestampMs = 940_000L, monotonicMs = 505_000L)

        val verdict = OdometerFixPolicy.judge(last, next)

        assertTrue("real motion accepted despite the wall-clock step-back", verdict is OdometerFixPolicy.Verdict.Accept)
        assertEquals(150.0, (verdict as OdometerFixPolicy.Verdict.Accept).deltaMeters, 0.5)
    }

    @Test
    fun `a monotonic clock that does not advance is a genuine ordering fault`() {
        val last = fix(0.0, accuracyMeters = 6.0, timestampMs = 1_000_000L, monotonicMs = 500_000L)

        // Wall time advances, monotonic time does not: the wall clock is the one lying.
        val sameInstant = fix(150.0, accuracyMeters = 6.0, timestampMs = 1_005_000L, monotonicMs = 500_000L)
        assertEquals(OdometerFixPolicy.Reason.NON_MONOTONIC_TIME, reasonOf(OdometerFixPolicy.judge(last, sameInstant)))

        val backwards = fix(150.0, accuracyMeters = 6.0, timestampMs = 1_005_000L, monotonicMs = 499_000L)
        assertEquals(OdometerFixPolicy.Reason.NON_MONOTONIC_TIME, reasonOf(OdometerFixPolicy.judge(last, backwards)))
    }

    @Test
    fun `a monotonic value on only one side falls back to wall time`() {
        // Tests and synthetic sources carry no monotonic clock; their behaviour is unchanged.
        val lastWallOnly = fix(0.0, accuracyMeters = 6.0, timestampMs = 0L)
        val nextBoth = fix(150.0, accuracyMeters = 6.0, timestampMs = 5_000L, monotonicMs = 5_000L)
        assertTrue(
            "wall-time fallback accepts the 30 m per second leg",
            OdometerFixPolicy.judge(lastWallOnly, nextBoth) is OdometerFixPolicy.Verdict.Accept,
        )

        val lastBoth = fix(0.0, accuracyMeters = 6.0, timestampMs = 0L, monotonicMs = 0L)
        val nextWallOnly = fix(1_000.0, accuracyMeters = 6.0, timestampMs = 1_000L)
        assertEquals(
            "wall time still catches the 1,000 m per second jump",
            OdometerFixPolicy.Reason.IMPLAUSIBLE_SPEED,
            reasonOf(OdometerFixPolicy.judge(lastBoth, nextWallOnly)),
        )
    }

    @Test
    fun `with no clock on either side the bounded jump check still applies`() {
        val last = fix(0.0, accuracyMeters = 6.0)
        assertEquals(
            OdometerFixPolicy.Reason.IMPLAUSIBLE_JUMP,
            reasonOf(OdometerFixPolicy.judge(last, fix(2_500.0, accuracyMeters = 6.0))),
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
