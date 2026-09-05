package cloud.trotter.dashbuddy.core.data.location

import cloud.trotter.dashbuddy.core.datastore.odometer.OdometerLocalDataSource
import cloud.trotter.dashbuddy.core.location.LocationDataSource
import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import timber.log.Timber

/**
 * #438 B5 (item 9) — per-session odometer anchors. Replaces the single global anchor + `resetSession()`
 * so two concurrent sessions accrue miles independently and a second session starting can't zero the
 * first's. `metadata.odometer` (the cumulative reading) is untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OdometerRepositoryPerSessionTest {

    private val locationUpdates = MutableSharedFlow<Coordinates>(extraBufferCapacity = 64)

    private val location: LocationDataSource = mock {
        on { this.locationUpdates } doReturn locationUpdates
    }

    private val local: OdometerLocalDataSource = mock {
        on { totalMetersFlow } doReturn flowOf(0.0)
        on { currentSessionIdFlow } doReturn flowOf(null)
        on { sessionAnchorFlow(any()) } doReturn flowOf(null)
    }

    private fun repo(dispatcher: TestDispatcher) =
        OdometerRepository(local, location, dispatcher)

    /** Records the level + message of every line the repository logs (#1057 round 2 — R3). */
    private class Recorder : Timber.Tree() {
        val lines = mutableListOf<Pair<Int, String>>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += priority to message
        }

        fun warns(): List<String> = lines.filter { it.first == android.util.Log.WARN }.map { it.second }
        fun infos(): List<String> = lines.filter { it.first == android.util.Log.INFO }.map { it.second }
    }

    private val recorder = Recorder()

    @Before
    fun plant() = Timber.plant(recorder)

    @After
    fun uproot() = Timber.uproot(recorder)

    // ~110 m north per step (0.001° latitude), comfortably over the 5 m gate. No timestamps: the
    // #1057 gate falls back to its bounded-jump check, which 110 m clears.
    private suspend fun drive(vararg latitudes: Double) {
        for (lat in latitudes) locationUpdates.emit(Coordinates(lat, 0.0))
    }

    /** Degrees of latitude for [meters] north — the same earth radius `Coordinates.distanceTo` uses. */
    private fun degreesNorth(meters: Double): Double = Math.toDegrees(meters / 6_371_000.0)

    private suspend fun emitFix(
        metersNorth: Double,
        accuracyMeters: Double?,
        timestampMs: Long?,
        monotonicMs: Long? = null,
    ) {
        locationUpdates.emit(
            Coordinates(
                latitude = degreesNorth(metersNorth),
                longitude = 0.0,
                accuracyMeters = accuracyMeters,
                timestampMs = timestampMs,
                monotonicMs = monotonicMs,
            )
        )
    }

    @Test
    fun `sessions A and B accrue independently, B starting does not reset A`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        // Session A anchors at 0.
        repo.startSessionTracking("A")
        drive(0.000, 0.001, 0.002) // two accrued deltas
        val milesAtB = repo.getCurrentMiles()
        assertTrue("some miles accrued before B", milesAtB > 0.0)

        // Session B starts mid-A. Its anchor is the CURRENT total — it must NOT move A's anchor (0).
        repo.startSessionTracking("B")
        assertEquals("B starts at ~0 miles", 0.0, repo.getCurrentSessionMiles("B"), 1e-6)

        // Drive further: both sessions accrue the SAME new delta.
        drive(0.002, 0.003, 0.004)
        val total = repo.getCurrentMiles()

        // A saw the WHOLE dash (anchor 0) — proof B's start didn't zero it (the old global-reset bug).
        assertEquals("A miles == full cumulative total", total, repo.getCurrentSessionMiles("A"), 1e-6)
        // B saw only the post-B leg.
        assertEquals("B miles == total − milesAtB", total - milesAtB, repo.getCurrentSessionMiles("B"), 1e-6)
        // And they differ by exactly A's pre-B accrual — independent anchors.
        assertEquals(
            "A − B == A's pre-B accrual",
            milesAtB,
            repo.getCurrentSessionMiles("A") - repo.getCurrentSessionMiles("B"),
            1e-6,
        )
    }

    @Test
    fun `re-arming a live session does not move its anchor (idempotent grace-resume)`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        repo.startSessionTracking("A")
        drive(0.000, 0.001, 0.002)
        val before = repo.getCurrentSessionMiles("A")

        // A grace-resume re-arm of the SAME session must be a no-op on the anchor (moving it would
        // zero the session's accrued miles — the exact concurrency bug the per-session anchor fixes).
        repo.startSessionTracking("A")
        assertEquals("anchor unchanged on re-arm", before, repo.getCurrentSessionMiles("A"), 1e-6)
    }

    @Test
    fun `getCurrentMiles is the raw cumulative reading — the metadata_odometer invariant is preserved`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        // No session anchoring at all — getCurrentMiles reports the raw cumulative total regardless,
        // and it equals sessionMilesFlow with a zero anchor (the projector's metadata.odometer input).
        drive(0.000, 0.001, 0.002)
        val cumulative = repo.getCurrentMiles()
        assertTrue("cumulative accrued", cumulative > 0.0)
        assertEquals("no-arg session flow with no anchor == cumulative", cumulative, repo.sessionMilesFlow.first(), 1e-6)
    }

    /**
     * #1057 — the fielded defect: one spurious fused fix ~1,457 km away added 905.37 mi in 18.4 min
     * straight into the persisted cumulative total. The gate must reject it AND refuse to let it
     * become the reference, so the good fix after it measures from the last ACCEPTED position.
     */
    @Test
    fun `a teleporting fix accrues nothing and never becomes the reference`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 8.0, timestampMs = 0L)          // reference
        emitFix(150.0, accuracyMeters = 8.0, timestampMs = 5_000L)    // good leg: +150 m
        val afterFirstLeg = repo.getCurrentMiles()

        // The 905-mile fix (1,457 km in 18.4 min ≈ 1,320 m/s).
        emitFix(1_457_000.0, accuracyMeters = 8.0, timestampMs = 1_109_000L)
        assertEquals("the teleport accrued nothing", afterFirstLeg, repo.getCurrentMiles(), 1e-9)

        // A good fix 300 m past the last ACCEPTED position: if the teleport had become the reference,
        // this would either accrue ~1,457 km back or be rejected as another teleport.
        emitFix(450.0, accuracyMeters = 8.0, timestampMs = 1_114_000L)
        val total = repo.getCurrentMiles()

        val expectedMeters = 150.0 + 300.0
        assertEquals(
            "only the two good legs accrued",
            expectedMeters * 0.000621371,
            total,
            1e-6,
        )
    }

    /** #918 — indoor multipath inside the fixes' own error radius is not motion. */
    @Test
    fun `stationary jitter inside the accuracy radius accrues nothing`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 25.0, timestampMs = 0L) // reference
        var t = 0L
        for (bounce in listOf(12.0, -9.0, 15.0, -14.0, 8.0, -11.0, 13.0)) {
            t += 3_000L
            emitFix(bounce, accuracyMeters = 25.0, timestampMs = t)
        }

        assertEquals("parked at a desk accrues zero miles", 0.0, repo.getCurrentMiles(), 1e-9)
    }

    /** A fix worse than the accuracy bound is rejected outright — it is never even the reference. */
    @Test
    fun `a poor-accuracy fix accrues nothing and does not anchor the next measurement`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 80.0, timestampMs = 0L)      // rejected: cannot seed a reference
        emitFix(500.0, accuracyMeters = 6.0, timestampMs = 5_000L) // first usable fix → reference
        assertEquals("nothing accrued yet", 0.0, repo.getCurrentMiles(), 1e-9)

        emitFix(650.0, accuracyMeters = 6.0, timestampMs = 10_000L) // +150 m
        assertEquals(
            "only the leg between the two usable fixes accrued",
            150.0 * 0.000621371,
            repo.getCurrentMiles(),
            1e-6,
        )
    }

    /**
     * The reference-does-not-move property, end to end: three 2 m creep steps under the 5 m floor land
     * as ONE 6 m accrual rather than being discarded (a drive-through line still counts).
     */
    @Test
    fun `slow creep under the floor still accumulates because the reference stays put`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 3.0, timestampMs = 0L) // reference; floor = 5 m
        emitFix(2.0, accuracyMeters = 3.0, timestampMs = 3_000L)
        assertEquals(0.0, repo.getCurrentMiles(), 1e-9)
        emitFix(4.0, accuracyMeters = 3.0, timestampMs = 6_000L)
        assertEquals(0.0, repo.getCurrentMiles(), 1e-9)
        emitFix(6.0, accuracyMeters = 3.0, timestampMs = 9_000L)

        assertEquals(
            "the whole 6 m displacement from the reference lands at once",
            6.0 * 0.000621371,
            repo.getCurrentMiles(),
            1e-6,
        )
    }

    // =============================================================================================
    // #1057 round 2
    // =============================================================================================

    /**
     * R1 — a malformed fix is not a measurement. Every bound in the gate is a comparison and every
     * comparison against NaN is false, so before the well-formedness check a NaN latitude fell
     * THROUGH to `Accept(NaN)` and `addMeters(NaN)` poisoned the persisted total permanently: no
     * later arithmetic recovers from it, and it survives a restart.
     */
    @Test
    fun `a malformed fix neither moves the total nor becomes the reference`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 8.0, timestampMs = 0L)       // reference
        emitFix(150.0, accuracyMeters = 8.0, timestampMs = 5_000L) // +150 m
        val afterFirstLeg = repo.getCurrentMiles()
        assertTrue("the good leg accrued", afterFirstLeg > 0.0)

        // A NaN latitude, and a NaN accuracy (which would otherwise make the jitter floor NaN, so a
        // 1 m stationary bounce reads as motion).
        locationUpdates.emit(Coordinates(Double.NaN, 0.0, accuracyMeters = 8.0, timestampMs = 8_000L))
        locationUpdates.emit(
            Coordinates(degreesNorth(151.0), 0.0, accuracyMeters = Double.NaN, timestampMs = 9_000L)
        )
        assertEquals("nothing accrued from the malformed fixes", afterFirstLeg, repo.getCurrentMiles(), 1e-9)
        assertTrue("the total is still a real number", repo.getCurrentMiles().isFinite())

        // And the reference did not move to them: this measures 150 m from the last ACCEPTED fix.
        emitFix(300.0, accuracyMeters = 8.0, timestampMs = 12_000L)
        assertEquals(
            "only the two good legs accrued",
            (150.0 + 150.0) * 0.000621371,
            repo.getCurrentMiles(),
            1e-6,
        )
        assertTrue(
            "the malformed fixes were reported as INVALID_FIX",
            recorder.warns().any { it.contains("INVALID_FIX") },
        )
    }

    /**
     * R3 — poor reception is a CONDITION, not an event. A canyon stretch used to emit one WARN per
     * rejected fix (720–1,800 an hour at the provider's cadence), drowning the exceptional
     * speed/jump rejects a WARN exists to surface (principle 7).
     */
    @Test
    fun `a 300-fix poor-accuracy stretch logs one entry WARN, three reminders and one recovery INFO`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 8.0, timestampMs = 0L) // reference
        var t = 0L
        repeat(300) {
            t += 3_000L
            emitFix(10.0, accuracyMeters = 60.0, timestampMs = t) // rejected: POOR_ACCURACY
        }
        val duringStreak = recorder.warns().size
        assertTrue("bounded WARNs, not one per fix (was $duringStreak)", duringStreak <= 4)
        assertEquals("one entry WARN plus one reminder per 100", 4, duringStreak)
        assertTrue("the episode is announced as a streak", recorder.warns().first().contains("entering a rejection streak"))
        assertTrue(
            "the reminders count fixes, not coordinates",
            recorder.warns().drop(1).all { it.startsWith("Odometer rejection streak: ") },
        )
        assertTrue("no recovery INFO while reception is still bad", recorder.infos().none { it.contains("recovered") })

        // Reception comes back: ONE closing INFO, and no further WARN.
        emitFix(150.0, accuracyMeters = 8.0, timestampMs = t + 3_000L)
        assertEquals("exactly one recovery INFO", 1, recorder.infos().count { it.contains("Odometer reception recovered") })
        assertEquals("the streak added no further WARN", duringStreak, recorder.warns().size)
        assertTrue("the recovery names the episode's size", recorder.infos().any { it.contains("300 rejected fixes") })
    }

    /** R3 — an isolated implausible fix is still exactly one WARN: the gate did not go quiet. */
    @Test
    fun `a single teleport amid accepted fixes logs exactly one WARN`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 8.0, timestampMs = 0L)
        emitFix(150.0, accuracyMeters = 8.0, timestampMs = 5_000L)
        emitFix(1_457_000.0, accuracyMeters = 8.0, timestampMs = 10_000L) // the 09-03 fault
        emitFix(300.0, accuracyMeters = 8.0, timestampMs = 15_000L)

        assertEquals("exactly one WARN", 1, recorder.warns().size)
        assertTrue(recorder.warns().single().contains("IMPLAUSIBLE_SPEED"))
    }

    /**
     * R3 — the episode gate must not hide a DIFFERENT fault. A teleport during a poor-reception
     * stretch is a new fact, not a repetition of the reason already reported.
     */
    @Test
    fun `a differing rejection reason inside a streak still WARNs individually`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 8.0, timestampMs = 0L) // reference
        var t = 0L
        repeat(5) {
            t += 3_000L
            emitFix(10.0, accuracyMeters = 60.0, timestampMs = t) // POOR_ACCURACY streak
        }
        assertEquals("only the entry WARN so far", 1, recorder.warns().size)

        t += 3_000L
        emitFix(1_457_000.0, accuracyMeters = 8.0, timestampMs = t) // IMPLAUSIBLE_SPEED

        assertEquals("the different reason is reported", 2, recorder.warns().size)
        assertTrue(recorder.warns()[0].contains("POOR_ACCURACY"))
        assertTrue(recorder.warns()[1].contains("IMPLAUSIBLE_SPEED"))
    }

    /**
     * R4 — an Ignored fix refreshes the reference's TIMING (never its position). The reference is an
     * accrual anchor, not a "last observation": leaving its timestamp stale let elapsed time grow
     * without bound while the device sat still, so a 1,457 km teleport after seven parked hours
     * implied a perfectly plausible 57.8 m/s and was ACCEPTED — the +905-mile fault, reachable
     * again through the jitter path alone.
     */
    @Test
    fun `seven hours of ignored jitter does not make a later teleport look plausible`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 25.0, timestampMs = 0L) // reference
        var t = 0L
        repeat(7) {
            t += 3_600_000L // one hour
            emitFix(12.0, accuracyMeters = 25.0, timestampMs = t) // inside the 25 m floor: Ignore
        }
        assertEquals("parked for seven hours accrues nothing", 0.0, repo.getCurrentMiles(), 1e-9)

        // 1,457 km three seconds after the last observation. Measured from the reference's ORIGINAL
        // timestamp that is 57.8 m/s — under the 67 m/s bound, i.e. accepted.
        emitFix(1_457_000.0, accuracyMeters = 8.0, timestampMs = t + 3_000L)

        assertEquals("the teleport accrued nothing", 0.0, repo.getCurrentMiles(), 1e-9)
        assertTrue(
            "and it was rejected for implausible speed",
            recorder.warns().any { it.contains("IMPLAUSIBLE_SPEED") },
        )
    }

    /** R4 — refreshing the reference's timing must not move its POSITION: creep still accumulates. */
    @Test
    fun `refreshing the reference timing on Ignore does not move the accrual anchor`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 3.0, timestampMs = 0L) // reference; floor = 5 m
        emitFix(2.0, accuracyMeters = 3.0, timestampMs = 3_000L)
        emitFix(4.0, accuracyMeters = 3.0, timestampMs = 6_000L)
        emitFix(6.0, accuracyMeters = 3.0, timestampMs = 9_000L)

        assertEquals(
            "the whole 6 m from the ORIGINAL reference position lands at once",
            6.0 * 0.000621371,
            repo.getCurrentMiles(),
            1e-6,
        )
    }

    /**
     * R2 — a wall-clock step-back (an NTP correction mid-drive) must not stall the odometer. Judged
     * on `Location.time` this leg is NON_MONOTONIC_TIME and every fix after it fails too until the
     * clock catches up: ~110 s of silently lost mileage per correction.
     */
    @Test
    fun `an NTP step-back mid-drive does not stall accrual when the monotonic clock advances`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repo = repo(dispatcher)
        repo.startTracking()

        emitFix(0.0, accuracyMeters = 6.0, timestampMs = 1_000_000L, monotonicMs = 500_000L)
        emitFix(150.0, accuracyMeters = 6.0, timestampMs = 1_005_000L, monotonicMs = 505_000L)
        // The clock steps 60 s BACKWARDS here; the monotonic clock keeps its 5 s cadence.
        emitFix(300.0, accuracyMeters = 6.0, timestampMs = 950_000L, monotonicMs = 510_000L)
        emitFix(450.0, accuracyMeters = 6.0, timestampMs = 955_000L, monotonicMs = 515_000L)

        assertEquals(
            "all three legs accrued",
            450.0 * 0.000621371,
            repo.getCurrentMiles(),
            1e-6,
        )
        assertTrue("nothing was rejected", recorder.warns().isEmpty())
    }

}
