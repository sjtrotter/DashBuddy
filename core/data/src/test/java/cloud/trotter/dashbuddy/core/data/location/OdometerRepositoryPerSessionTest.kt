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

    // ~110 m north per step (0.001° latitude), comfortably over the 5 m gate.
    private suspend fun drive(vararg latitudes: Double) {
        for (lat in latitudes) locationUpdates.emit(Coordinates(lat, 0.0))
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
}
