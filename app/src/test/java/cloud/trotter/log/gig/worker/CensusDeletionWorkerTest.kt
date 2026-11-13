package cloud.trotter.log.gig.worker

import androidx.work.ListenableWorker
import cloud.trotter.log.gig.core.network.census.DeleteResult
import cloud.trotter.log.gig.core.network.census.HealthResult
import cloud.trotter.log.gig.core.network.census.UploadResult
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.census.CensusDeletion.State
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * #1622: "Delete my census data" ([cloud.trotter.log.gig.core.data.census.CensusDataDeletion]) and the worker that
 * retries it. The phone's queue is cleared before the request goes, a pending request is sent before anything else and
 * blocks every other send, and the server's confirmation is what the screen shows.
 */
@RunWith(RobolectricTestRunner::class)
class CensusDeletionWorkerTest {
    private val deleted = DeleteResult.Deleted(rows = 12, deletedDay = "2026-11-13")

    @Test fun `delete clears the phone's queue first, calls the endpoint and stores the server's confirmation`() = runTest {
        val h = harness()
        h.initialize()
        h.queue(2)
        h.health()
        var queuedAtRequest = -1
        h.api.beforeDelete = { queuedAtRequest = h.queued.size }
        h.api.deletions += deleted

        val result = h.deletion.delete()

        assertEquals("the queue is empty before the request goes", 0, queuedAtRequest)
        assertEquals(State.DELETED, result.state)
        assertEquals(12, result.rows)
        assertEquals("2026-11-13", result.deletedDay)
        assertEquals(result, h.preferences.censusDeletion.first())
        assertEquals(listOf(h.credential.installId), h.api.deletedIds)
        assertTrue("no unsent daily count survives", h.healthSink.snapshot().rows.isEmpty())
        assertEquals("a waiting manual run is cancelled", 1, h.scheduler.cancelled)
        assertEquals("nothing is left to retry", 0, h.scheduler.deletions)
        assertEquals("stopping is the switch; deleting leaves consent alone", CensusConsent.ON, h.preferences.censusConsent.first())
    }

    @Test fun `a deletion made while an upload waits cancels it, and the upload sends nothing from before`() = runTest {
        val h = harness()
        h.initialize()
        h.queue(3)
        h.health()
        h.api.deletions += deleted
        h.deletion.delete()

        val daily = listOf(CensusWorkScheduler.DAILY_TAG)
        h.preferences.setCensusNextRunAtMillis(System.currentTimeMillis() - 60_000)
        assertEquals(ListenableWorker.Result.success(), h.worker(daily).doWork())

        assertEquals("only the deletion went", listOf("delete"), h.api.stages)
        assertTrue(h.api.bodies.isEmpty())
        assertTrue(h.api.healthBodies.isEmpty())
        assertTrue(h.queued.isEmpty())
    }

    @Test fun `an offline deletion stays pending, blocks every send, and goes first when the phone is back online`() = runTest {
        val h = harness()
        h.initialize()
        h.queue()
        h.api.deletions += DeleteResult.TransportFailure("IOException")

        assertEquals(State.PENDING, h.deletion.delete().state)
        assertEquals("its own retry run is queued", 1, h.scheduler.deletions)
        assertTrue(h.queued.isEmpty())

        // Queued after the request, while it is pending.
        val later = h.queueBatch(1)
        h.health()
        h.api.deletions += DeleteResult.ServerUnavailable(503)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals("a pending request blocks the upload", listOf("delete", "delete"), h.api.stages)
        assertTrue(h.api.healthBodies.isEmpty())
        assertEquals(listOf(later), h.queued)
        assertTrue(h.preferences.censusDeletion.first()!!.pending)

        h.api.deletions += deleted
        h.api.healthResults += HealthResult.Accepted(1, emptyMap())
        h.api.uploads += UploadResult.Duplicate
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals("the deletion first, then what was queued after it", listOf("delete", "delete", "delete", "skeletons"), h.api.stages)
        assertEquals(State.DELETED, h.preferences.censusDeletion.first()?.state)
        assertTrue(h.queued.isEmpty())
    }

    @Test fun `the deletion run retries until the request resolves and sends nothing else`() = runTest {
        val h = harness()
        h.initialize()
        val deletionRun = listOf(CensusWorkScheduler.DELETION_TAG)
        assertEquals("nothing pending: no request", ListenableWorker.Result.success(), h.worker(deletionRun).doWork())
        assertEquals(0, h.factoryCalls)

        h.preferences.setCensusDeletion(CensusDeletion(1L, State.PENDING, localCleared = true))
        h.queue()
        h.api.deletions += DeleteResult.RateLimited(60)
        assertEquals(ListenableWorker.Result.retry(), h.worker(deletionRun).doWork())
        h.api.deletions += deleted
        assertEquals(ListenableWorker.Result.success(), h.worker(deletionRun).doWork())
        assertEquals("a deletion run uploads nothing", listOf("delete", "delete"), h.api.stages)
        assertEquals(1, h.queued.size)
    }

    @Test fun `a deletion goes even with the census off`() = runTest {
        val h = harness()
        h.initialize(enabled = false)
        h.api.deletions += deleted
        assertEquals(State.DELETED, h.deletion.delete().state)
        assertEquals(listOf("delete"), h.api.stages)
    }

    @Test fun `not enrolled - the server holds nothing, and no request is made`() = runTest {
        val h = harness()
        h.initialize()
        whenever(h.credentials.current()).thenReturn(null)
        h.queue()
        assertEquals(State.NOTHING_HELD, h.deletion.delete().state)
        assertEquals(0, h.factoryCalls)
        assertTrue("the queue is cleared all the same", h.queued.isEmpty())
    }

    @Test fun `a 401 means the server no longer knows this ID - nothing held, and the next run enrols a fresh one`() = runTest {
        val h = harness()
        h.initialize()
        h.api.deletions += DeleteResult.Unauthorized(System.currentTimeMillis())
        assertEquals(State.NOTHING_HELD, h.deletion.delete().state)
        verify(h.credentials).wipe()
        assertEquals(CensusConsent.ON, h.preferences.censusConsent.first())
    }

    @Test fun `a 401 from a skewed clock keeps the request pending`() = runTest {
        val h = harness()
        h.initialize()
        h.api.deletions += DeleteResult.Unauthorized(System.currentTimeMillis() + 600_000)
        assertEquals(State.PENDING, h.deletion.delete().state)
    }

    @Test fun `a revoked ID cannot delete - the census goes off and the screen says so`() = runTest {
        val h = harness()
        h.initialize()
        h.api.deletions += DeleteResult.Revoked
        assertEquals(State.NOT_POSSIBLE, h.deletion.delete().state)
        assertFalse(h.preferences.censusUploadEnabled.first())
        verify(h.credentials).wipe()
    }

    @Test fun `an unreadable stored request fails closed as pending`() {
        assertEquals(State.PENDING, State.fromWire("garbled"))
        assertEquals(State.PENDING, State.fromWire(null))
    }
}
