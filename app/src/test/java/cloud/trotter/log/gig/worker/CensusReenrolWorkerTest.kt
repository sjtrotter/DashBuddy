package cloud.trotter.log.gig.worker

import androidx.work.ListenableWorker
import cloud.trotter.log.gig.core.network.census.CensusBudget
import cloud.trotter.log.gig.core.network.census.HealthResult
import cloud.trotter.log.gig.core.network.census.UploadResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

/**
 * #1622: a 401 re-enrols only when nothing in the run proved the server verified this credential. Evidence from any
 * stage (health, outlines, envelopes) counts for every later stage, so a credential that worked this run is kept.
 */
@RunWith(RobolectricTestRunner::class)
class CensusReenrolWorkerTest {
    private val budget = CensusBudget(299, 1000, 39, 300)

    @Test fun `an accepted health report proves the credential, so a later upload 401 keeps it`() = runTest {
        val h = harness()
        h.initialize()
        h.health()
        h.queue()
        h.api.healthResults += HealthResult.Accepted(1, emptyMap())
        h.api.uploads.addAll(List(2) { UploadResult.Unauthorized(System.currentTimeMillis()) })
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        verify(h.credentials, never()).mint()
        assertTrue(h.api.enrolledIds.isEmpty())
        assertEquals("the batch stays queued", 1, h.queued.size)
    }

    @Test fun `accepted outlines prove the credential, so an envelope 401 keeps it`() = runTest {
        val h = harness()
        h.initialize()
        h.queue()
        h.queueEnvelopes()
        h.api.uploads += UploadResult.Accepted(1, 0, emptyMap(), budget)
        h.api.envelopeResults.addAll(List(2) { UploadResult.Unauthorized(System.currentTimeMillis()) })
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        verify(h.credentials, never()).mint()
        assertTrue(h.api.enrolledIds.isEmpty())
    }
}
