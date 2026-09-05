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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

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

    // ~110 m north per step (0.001° latitude), comfortably over the 5 m gate. No timestamps: the
    // #1057 gate falls back to its bounded-jump check, which 110 m clears.
    private suspend fun drive(vararg latitudes: Double) {
        for (lat in latitudes) locationUpdates.emit(Coordinates(lat, 0.0))
    }

    /** Degrees of latitude for [meters] north — the same earth radius `Coordinates.distanceTo` uses. */
    private fun degreesNorth(meters: Double): Double = Math.toDegrees(meters / 6_371_000.0)

    private suspend fun emitFix(metersNorth: Double, accuracyMeters: Double?, timestampMs: Long?) {
        locationUpdates.emit(
            Coordinates(
                latitude = degreesNorth(metersNorth),
                longitude = 0.0,
                accuracyMeters = accuracyMeters,
                timestampMs = timestampMs,
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
}
