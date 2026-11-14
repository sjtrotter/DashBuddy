package cloud.trotter.log.gig.worker

import androidx.work.ListenableWorker
import cloud.trotter.log.gig.core.network.census.DeleteResult
import cloud.trotter.log.gig.core.network.census.EnrolResult
import kotlinx.serialization.json.JsonObject
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
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
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
        assertEquals(listOf("2026-11-13"), result.confirmedDays)
        assertEquals(result, h.preferences.censusDeletion.first())
        assertEquals(listOf(h.credential.installId), h.api.deletedIds)
        assertTrue("no unsent daily count survives", h.healthSink.snapshot().rows.isEmpty())
        assertEquals("a waiting manual run is cancelled", 1, h.scheduler.cancelled)
        assertEquals("its retry run was queued before the first attempt", 1, h.scheduler.deletions)
        assertTrue(result.resolved)
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
        assertEquals(CensusDeletion.Waiting.UNREACHABLE, h.preferences.censusDeletion.first()?.waiting)
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

    @Test fun `no census ID at all - no request is made, and the result says so as a fact about the request`() = runTest {
        val h = harness()
        h.initialize()
        whenever(h.credentials.current()).thenReturn(null)
        h.queue()
        assertEquals(State.NOTHING_SENT, h.deletion.delete().state)
        assertEquals(0, h.factoryCalls)
        assertTrue("the queue is cleared all the same", h.queued.isEmpty())
    }

    @Test fun `a 401 never counts as deleted or nothing held - the ID is kept and the request retried`() = runTest {
        val h = harness()
        h.initialize()
        for (date in listOf(System.currentTimeMillis(), null, System.currentTimeMillis() + 600_000)) {
            h.api.deletions += DeleteResult.Unauthorized(date)
            val result = h.deletion.delete()
            assertEquals(State.PENDING, result.state)
            assertEquals(CensusDeletion.Waiting.UNCONFIRMED, result.waiting)
            assertEquals(listOf(h.credential.installId), result.targets?.map { it.installId })
        }
        verify(h.credentials, never()).wipe()
        assertEquals("a pending request blocks uploads", ListenableWorker.Result.success(), run {
            h.queueBatch(1)
            h.api.deletions += DeleteResult.Unauthorized(System.currentTimeMillis())
            h.worker().doWork()
        })
        assertTrue(h.api.bodies.isEmpty())

        h.api.deletions += deleted
        assertEquals(ListenableWorker.Result.success(), h.worker(listOf(CensusWorkScheduler.DELETION_TAG)).doWork())
        assertEquals(State.DELETED, h.preferences.censusDeletion.first()?.state)
    }

    @Test fun `a 401 recovers by enrolling the same ID and key, then resolves only on the DELETE's own 200`() = runTest {
        val h = harness()
        h.initialize()
        h.queue()
        h.api.deletions += DeleteResult.Unauthorized(System.currentTimeMillis())
        h.api.enrollments += EnrolResult.Enrolled(JsonObject(emptyMap()))
        h.api.deletions += deleted
        val result = h.deletion.delete()
        assertEquals("the same ID re-enrols with its own key", listOf(h.credential.installId), h.api.enrolledIds)
        assertEquals(State.DELETED, result.state)
        assertEquals(listOf(h.credential.installId, h.credential.installId), h.api.deletedIds)
        verify(h.credentials, never()).mint()
    }

    @Test fun `a recovery enrolment that fails, or a second 401, leaves the request pending and uploads blocked`() = runTest {
        val h = harness()
        h.initialize()
        h.api.deletions += DeleteResult.Unauthorized(System.currentTimeMillis())
        h.api.enrollments += EnrolResult.ServerUnavailable(503)
        assertEquals(State.PENDING, h.deletion.delete().state)
        assertEquals("no DELETE after a failed enrolment", 1, h.api.deletedIds.size)

        h.queueBatch(1)
        h.api.deletions += DeleteResult.Unauthorized(System.currentTimeMillis())
        h.api.enrollments += EnrolResult.Enrolled(JsonObject(emptyMap()))
        h.api.deletions += DeleteResult.Unauthorized(System.currentTimeMillis())
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals("an enrolment alone never counts as deletion", State.PENDING, h.preferences.censusDeletion.first()?.state)
        assertTrue(h.api.bodies.isEmpty())
    }

    @Test fun `an identity reset before the request keeps the old ID's key, and the request deletes under it`() = runTest {
        val h = harness()
        h.initialize()
        val old = h.credential
        whenever(h.credentials.current()).thenReturn(null)
        whenever(h.credentials.retiredIds()).thenReturn(listOf(old.installId))
        whenever(h.credentials.retired(old.installId)).thenReturn(old)
        h.api.deletions += deleted

        val result = h.deletion.delete()
        assertEquals(State.DELETED, result.state)
        assertEquals(listOf(old.installId), h.api.deletedIds)
        verify(h.credentials).forgetRetired(old.installId)
    }

    /** A retired identity A (an identity reset) and the current one B, both to delete. */
    private suspend fun CensusWorkerHarness.retiredAndCurrent(): CensusCredentialStore.Credential {
        initialize()
        val a = credential.copy(installId = "aaaaaaaa-1234-4123-8123-123456789abc")
        whenever(credentials.retiredIds()).thenReturn(listOf(a.installId))
        whenever(credentials.retired(a.installId)).thenReturn(a)
        return a
    }

    @Test fun `a confirmation is saved before the retired key is forgotten, and a failed forget is finished later`() = runTest {
        val h = harness()
        val a = h.retiredAndCurrent()
        whenever(h.credentials.forgetRetired(a.installId)).thenAnswer { throw java.io.IOException("disk") }.thenReturn(Unit)
        h.api.deletions.addAll(listOf(DeleteResult.Deleted(3, "2026-11-12"), DeleteResult.Deleted(4, "2026-11-13")))

        val result = h.deletion.delete()
        assertEquals("a failed forget never stops the next ID", State.DELETED, result.state)
        assertEquals(listOf(a.installId, h.credential.installId), h.api.deletedIds)
        assertEquals(7, result.rows)

        // The next load re-runs the idempotent cleanup: the confirmed ID's key, still kept, is forgotten now.
        assertTrue(h.deletion.resumePending())
        verify(h.credentials, times(2)).forgetRetired(a.installId)
    }

    @Test fun `a confirmation that could not be saved keeps the key, and the retry still deletes both IDs`() = runTest {
        val h = harness()
        val a = h.retiredAndCurrent()
        val targetsKey = androidx.datastore.preferences.core.stringPreferencesKey("census_deletion_targets_json")
        h.memory.failOnce = { prefs -> prefs[targetsKey]?.contains("\"DELETED\"") == true }
        h.api.deletions += DeleteResult.Deleted(3, "2026-11-12")

        val interrupted = h.deletion.delete()
        assertTrue("still pending: A's confirmation was not saved", interrupted.pending)
        verify(h.credentials, never()).forgetRetired(a.installId)

        h.api.deletions.addAll(listOf(DeleteResult.Deleted(0, "2026-11-12"), DeleteResult.Deleted(4, "2026-11-13")))
        assertEquals(ListenableWorker.Result.success(), h.worker(listOf(CensusWorkScheduler.DELETION_TAG)).doWork())
        val done = requireNotNull(h.preferences.censusDeletion.first())
        assertEquals(State.DELETED, done.state)
        assertEquals("A again (idempotent), then B", listOf(a.installId, a.installId, h.credential.installId), h.api.deletedIds)
        verify(h.credentials).forgetRetired(a.installId)
    }

    @Test fun `an ID whose key is gone is not possible on its own, and the others are still deleted`() = runTest {
        val h = harness()
        val a = h.retiredAndCurrent()
        whenever(h.credentials.retired(a.installId)).thenReturn(null)
        h.api.deletions += deleted
        val result = h.deletion.delete()
        assertEquals(State.DELETED, result.state)
        assertEquals(1, result.notPossibleCount)
        assertEquals(listOf(h.credential.installId), h.api.deletedIds)
    }

    @Test fun `each ID keeps its own confirmation day`() = runTest {
        val h = harness()
        h.retiredAndCurrent()
        h.api.deletions.addAll(listOf(DeleteResult.Deleted(3, "2026-11-16"), DeleteResult.Deleted(4, "2026-11-17")))
        val result = h.deletion.delete()
        assertEquals(listOf(3 to "2026-11-16", 4 to "2026-11-17"), result.targets?.map { it.rows to it.day })
        assertEquals(listOf("2026-11-16", "2026-11-17"), result.confirmedDays)
    }

    @Test fun `a nothing-sent result is cleared once the phone enrols`() = runTest {
        val h = harness()
        h.initialize()
        whenever(h.credentials.current()).thenReturn(null)
        whenever(h.credentials.pending()).thenReturn(null)
        assertEquals(State.NOTHING_SENT, h.deletion.delete().state)

        whenever(h.credentials.mint()).thenReturn(h.credential.copy(enrolled = false))
        h.api.enrollments += EnrolResult.Enrolled(JsonObject(emptyMap()))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(null, h.preferences.censusDeletion.first())
    }

    @Test fun `a server without the endpoint yet keeps the request waiting, with the reason the screen shows`() = runTest {
        val h = harness()
        h.initialize()
        h.api.deletions += DeleteResult.NotAvailable(404)
        val result = h.deletion.delete()
        assertEquals(State.PENDING, result.state)
        assertEquals(CensusDeletion.Waiting.NOT_YET, result.waiting)
        verify(h.credentials, never()).wipe()
    }

    @Test fun `a pending request is only ever sent with the ID it was made for`() = runTest {
        val h = harness()
        h.initialize()
        h.api.deletions += DeleteResult.TransportFailure("IOException")
        assertEquals(listOf(h.credential.installId), h.deletion.delete().targets?.map { it.installId })

        val other = h.credential.copy(installId = "87654321-1234-4123-8123-123456789abc")
        whenever(h.credentials.current()).thenReturn(other)
        assertEquals(ListenableWorker.Result.success(), h.worker(listOf(CensusWorkScheduler.DELETION_TAG)).doWork())
        assertEquals("never sent under another ID", listOf(h.credential.installId), h.api.deletedIds)
        assertEquals(State.NOT_POSSIBLE, h.preferences.censusDeletion.first()?.state)
    }

    @Test fun `a failed clear keeps uploads blocked after the server's confirmation until a retry clears the queue`() = runTest {
        val h = harness()
        h.initialize()
        h.queue()
        whenever(h.spool.clear()).thenAnswer { throw java.io.IOException("disk") }
            .thenAnswer { h.queued.clear(); h.pending = null; Unit }
        h.api.deletions += deleted
        val result = h.deletion.delete()
        assertEquals(State.DELETED, result.state)
        assertFalse("not resolved: the queue is still there", result.resolved)

        // The next run clears it first, sends no second deletion and uploads nothing queued before the request.
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertTrue(h.queued.isEmpty())
        assertTrue(h.api.bodies.isEmpty())
        assertEquals(listOf("delete"), h.api.stages)
        assertTrue(h.preferences.censusDeletion.first()!!.resolved)
    }

    @Test fun `app start queues the retry run again for an unresolved request, and not for a resolved one`() = runTest {
        val h = harness()
        h.initialize()
        h.preferences.setCensusDeletion(CensusDeletion(1L, State.PENDING))
        h.deletion.reconcile().join()
        assertEquals(1, h.scheduler.deletions)
        h.preferences.setCensusDeletion(CensusDeletion(1L, State.DELETED, localCleared = true))
        h.deletion.reconcile().join()
        assertEquals(1, h.scheduler.deletions)
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
