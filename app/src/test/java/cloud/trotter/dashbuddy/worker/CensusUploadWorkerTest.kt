package cloud.trotter.dashbuddy.worker

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.dashbuddy.core.data.census.CensusCredentialStore
import cloud.trotter.dashbuddy.core.data.census.CensusCredentialStore.Credential
import cloud.trotter.dashbuddy.core.data.census.CensusSpool
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.dashbuddy.core.network.census.CensusApiFactory
import cloud.trotter.dashbuddy.core.network.census.CensusApi
import cloud.trotter.dashbuddy.core.network.census.CensusBudget
import cloud.trotter.dashbuddy.core.network.census.CensusTransport
import cloud.trotter.dashbuddy.core.network.census.EnrolResult
import cloud.trotter.dashbuddy.core.network.census.PolicyResult
import cloud.trotter.dashbuddy.core.network.census.UploadResult
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
class CensusUploadWorkerTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class FakeApi : CensusTransport {
        val enrollments = ArrayDeque<EnrolResult>()
        val uploads = ArrayDeque<UploadResult>()
        val enrolledIds = mutableListOf<String>()
        val batches = mutableListOf<String>()
        val bodies = mutableListOf<List<String>>()
        var beforeUpload: (String, List<String>) -> Unit = { _, _ -> }
        override suspend fun enrol(cred: Bearer.Credential, appVersion: String): EnrolResult {
            enrolledIds += cred.installId
            return enrollments.removeFirst()
        }
        override suspend fun policy(): PolicyResult = PolicyResult.Available(JsonObject(emptyMap()))
        override suspend fun uploadSkeletons(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult {
            beforeUpload(batchId, itemsJson)
            batches += batchId
            bodies += itemsJson
            return uploads.removeFirst()
        }
    }

    private class Scheduler : CensusUploadScheduler {
        var deadline = 0L
        override fun enqueueNow() = Unit
        override fun deferUntil(epochMillis: Long) { deadline = epochMillis }
    }

    private class Harness {
        val preferences = DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), true, Dispatchers.Unconfined)
        val credentials: CensusCredentialStore = mock()
        val spool: CensusSpool = mock()
        val api = FakeApi()
        val scheduler = Scheduler()
        val stats = CensusUploadStats()
        val queued = mutableListOf<List<CensusSpool.Spooled>>()
        var pending: CensusSpool.InFlight? = null
        private var sequence = 0
        var factoryCalls = 0
        val credential = Credential("12345678-1234-4123-8123-123456789abc", InstallSecret.encode(ByteArray(32)), true)
        private val factory = object : CensusApiFactory() {
            override fun create(baseUrl: String): CensusTransport {
                factoryCalls++
                return api
            }
        }
        private val lock = CensusUploadLock()

        suspend fun initialize(enabled: Boolean = true) {
            preferences.setCensusUploadEnabled(enabled)
            whenever(credentials.current()).thenReturn(credential)
            whenever(spool.take(100, 900 * 1024)).thenAnswer {
                val retained = pending?.ids?.mapNotNull { id -> queued.flatten().find { it.id == id } }
                if (!retained.isNullOrEmpty()) retained else {
                    pending = null
                    queued.firstOrNull() ?: emptyList<CensusSpool.Spooled>()
                }
            }
            whenever(spool.inFlight()).thenAnswer { pending }
            whenever(spool.markInFlight(any(), any())).thenAnswer {
                pending = CensusSpool.InFlight(it.getArgument(1), it.getArgument<Collection<String>>(0).toList())
                Unit
            }
            whenever(spool.clearInFlight()).thenAnswer { pending = null; Unit }
            whenever(spool.remove(any())).thenAnswer {
                val ids = it.getArgument<Collection<String>>(0)
                val remaining = queued.map { batch -> batch.filterNot { item -> item.id in ids } }.filter { batch -> batch.isNotEmpty() }
                queued.clear()
                queued.addAll(remaining)
                Unit
            }
            api.beforeUpload = { batchId, json ->
                val persisted = requireNotNull(pending)
                assertEquals(batchId, persisted.batchId)
                assertEquals(json, persisted.ids.map { id -> queued.flatten().single { it.id == id }.itemJson })
            }
        }

        fun queue(count: Int = 1) {
            repeat(count) { queueBatch(1) }
        }

        fun queueBatch(count: Int): List<CensusSpool.Spooled> {
            val items = List(count) {
                val index = ++sequence
                CensusSpool.Spooled("1-$index.json", index.toString().padStart(64, '0'), "{\"item\":$index}")
            }
            queued += items
            return items
        }

        fun worker(): CensusUploadWorker = TestListenableWorkerBuilder<CensusUploadWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    CensusUploadWorker(appContext, workerParameters, preferences, credentials, spool, factory, scheduler, stats, lock)
            }).build()
    }

    @Test fun `disabled does not create API read credentials or drain spool`() = runTest {
        val h = Harness()
        h.initialize(enabled = false)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(0, h.factoryCalls)
        verify(h.credentials, never()).current()
        verify(h.spool, never()).take(any(), any())
    }

    @Test fun `fresh enrollment is persisted before upload and lost replies reuse pending identity`() = runTest {
        val h = Harness()
        h.initialize()
        whenever(h.credentials.current()).thenReturn(null)
        whenever(h.credentials.pending()).thenReturn(h.credential.copy(enrolled = false))
        h.api.enrollments += EnrolResult.ServerUnavailable(503)
        h.api.enrollments += EnrolResult.Enrolled(JsonObject(emptyMap()))
        assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(listOf(h.credential.installId, h.credential.installId), h.api.enrolledIds)
        verify(h.credentials, never()).mint()
        verify(h.credentials).markEnrolled()
        assertEquals("{}", h.preferences.censusPolicy.first())
    }

    @Test fun `install conflict mints only once more and enrol unauthorized retries`() = runTest {
        val h = Harness()
        h.initialize()
        whenever(h.credentials.current()).thenReturn(null)
        val pending = h.credential.copy(enrolled = false)
        val replacement = pending.copy(installId = "87654321-1234-4123-8123-123456789abc")
        whenever(h.credentials.mint()).thenReturn(pending, replacement)
        h.api.enrollments += EnrolResult.InstallExists
        h.api.enrollments += EnrolResult.Unauthorized
        assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
        verify(h.credentials, times(2)).mint()
        assertNotEquals(h.api.enrolledIds[0], h.api.enrolledIds[1])
        verify(h.credentials, never()).markEnrolled()
    }

    @Test fun `second install conflict stops without another mint`() = runTest {
        val h = Harness()
        h.initialize()
        whenever(h.credentials.current()).thenReturn(null)
        whenever(h.credentials.pending()).thenReturn(h.credential.copy(enrolled = false))
        whenever(h.credentials.mint()).thenReturn(h.credential.copy(enrolled = false))
        h.api.enrollments.addAll(listOf(EnrolResult.InstallExists, EnrolResult.InstallExists))
        assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
        verify(h.credentials).mint()
    }

    @Test fun `unusable credential remints and enrolls`() = runTest {
        val h = Harness()
        h.initialize()
        whenever(h.credentials.current()).thenThrow(CensusCredentialStore.Unusable())
        whenever(h.credentials.mint()).thenReturn(h.credential.copy(enrolled = false))
        h.api.enrollments += EnrolResult.Enrolled(JsonObject(emptyMap()))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        verify(h.credentials).mint()
        verify(h.credentials).markEnrolled()
    }

    @Test fun `accepted duplicate and batch quality remove items and count outcomes`() = runTest {
        val h = Harness()
        h.initialize()
        h.queue(4)
        h.api.uploads += UploadResult.Accepted(1, 2, mapOf("bad_hash" to 1), CensusBudget(200, 1000, 30, 300))
        h.api.uploads += UploadResult.Duplicate
        h.api.uploads += UploadResult.BatchQuality(mapOf("bad_kind" to 1))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(3, h.api.batches.size)
        assertEquals(1, h.queued.size) // At most three batches per run.
        assertEquals(1L, h.stats.uploaded.get())
        assertEquals(3L, h.stats.duplicate.get())
        assertEquals(mapOf("bad_hash" to 1L, "bad_kind" to 1L), h.stats.rejectedCounts())
    }

    @Test fun `429 deadlines persist and are honored by subsequent manual runs`() = runTest {
        for (result in listOf(UploadResult.RateLimited(3600), UploadResult.BudgetExhausted(3600))) {
            val h = Harness()
            h.initialize()
            h.queue()
            h.api.uploads += result
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertTrue(h.scheduler.deadline > System.currentTimeMillis())
            assertEquals(h.scheduler.deadline, h.preferences.nextAllowedAtMillis.first())
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(1, h.factoryCalls)
            assertEquals(1, h.api.batches.size)
            assertEquals(1, h.queued.size)
            h.preferences.setNextAllowedAtMillis(System.currentTimeMillis() - 1)
            h.api.uploads += UploadResult.Duplicate
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(2, h.factoryCalls)
            assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun `unauthorized resigns once with same batch and bytes`() = runTest {
        val h = Harness()
        h.initialize()
        h.queue()
        h.api.uploads += UploadResult.Unauthorized(System.currentTimeMillis())
        h.api.uploads += UploadResult.Duplicate
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(2, h.api.batches.size)
        assertEquals(h.api.batches[0], h.api.batches[1])
        assertEquals(h.api.bodies[0], h.api.bodies[1])
        assertTrue(h.queued.isEmpty())
    }

    @Test fun `second unauthorized stops without a third request and keeps in flight`() = runTest {
        val h = Harness()
        h.initialize()
        h.queue()
        h.api.uploads.addAll(listOf(
            UploadResult.Unauthorized(System.currentTimeMillis()),
            UploadResult.Unauthorized(System.currentTimeMillis()),
            UploadResult.Duplicate,
        ))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(2, h.api.batches.size)
        assertEquals(h.api.batches[0], h.api.batches[1])
        assertEquals(h.api.bodies[0], h.api.bodies[1])
        assertEquals(2L, h.stats.uploadFailures.get())
        assertEquals(h.api.batches[0], h.pending?.batchId)
        verify(h.spool, never()).remove(any())
        verify(h.spool, never()).clearInFlight()
    }

    @Test fun `resign allowance is shared by all batches in a run and resets next run`() = runTest {
        val h = Harness()
        h.initialize()
        h.queue(2)
        h.api.uploads.addAll(listOf(
            UploadResult.Unauthorized(System.currentTimeMillis()),
            UploadResult.Duplicate,
            UploadResult.Unauthorized(System.currentTimeMillis()),
        ))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(3, h.api.batches.size)
        assertEquals(1, h.queued.size)
        h.api.uploads.addAll(listOf(UploadResult.Unauthorized(System.currentTimeMillis()), UploadResult.Duplicate))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(5, h.api.batches.size)
        assertTrue(h.queued.isEmpty())
        assertNull(h.pending)
    }

    @Test fun `clock skew stops before resign and repeated unauthorized retains spool`() = runTest {
        for (skew in listOf(true, false)) {
            val h = Harness()
            h.initialize()
            h.queue()
            val date = System.currentTimeMillis() + if (skew) 600_000 else 0
            h.api.uploads.addAll(listOf(UploadResult.Unauthorized(date), UploadResult.Unauthorized(date)))
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(if (skew) 1 else 2, h.api.batches.size)
            assertEquals(1, h.queued.size)
        }
    }

    @Test fun `revoked wipes credentials disables toggle and stops`() = runTest {
        val h = Harness()
        h.initialize()
        h.queue()
        h.api.uploads += UploadResult.Revoked
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        verify(h.credentials).wipe()
        assertFalse(h.preferences.censusUploadEnabled.first())
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(1, h.api.batches.size)
    }

    @Test fun `server and transport failures retry without removing records`() = runTest {
        for (result in listOf(UploadResult.ServerUnavailable(503), UploadResult.TransportFailure("IOException"))) {
            val h = Harness()
            h.initialize()
            h.queue()
            h.api.uploads += result
            assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
            assertEquals(1, h.queued.size)
            assertEquals(1L, h.stats.uploadFailures.get())
        }
    }

    @Test fun `transport failure retries original batch before new arrivals with original batch id`() = runTest {
        val h = Harness()
        h.initialize()
        val a = h.queueBatch(1)
        h.api.uploads += UploadResult.TransportFailure("IOException")
        assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
        val originalBatchId = h.api.batches.single()
        assertEquals(CensusApi.batchId(a.map { it.fingerprint }), originalBatchId)
        val b = h.queueBatch(1)
        // Fresh selection would now return both A and B in one batch.
        h.queued.clear()
        h.queued += a + b
        h.api.uploads.addAll(listOf(UploadResult.Duplicate, UploadResult.Duplicate))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(listOf(a, a, b).map { items -> items.map { it.itemJson } }, h.api.bodies)
        assertEquals(listOf(originalBatchId, originalBatchId, CensusApi.batchId(b.map { it.fingerprint })), h.api.batches)
        assertTrue(h.queued.isEmpty())
        assertNull(h.pending)
        verify(h.spool, times(2)).clearInFlight()
    }

    @Test fun `413 recursively halves an odd batch in the same run and drops only oversized singleton`() = runTest {
        val h = Harness()
        h.initialize()
        val items = h.queueBatch(3)
        val warnings = mutableListOf<String>()
        val tree = warningTree(warnings)
        Timber.plant(tree)
        try {
            h.api.uploads.addAll(listOf(
                UploadResult.PayloadTooLarge, UploadResult.Duplicate,
                UploadResult.PayloadTooLarge, UploadResult.PayloadTooLarge, UploadResult.Duplicate,
            ))
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(listOf(items, items.take(1), items.drop(1), listOf(items[1]), listOf(items[2]))
                .map { batch -> batch.map { it.itemJson } }, h.api.bodies)
            assertEquals(listOf("census item oversized bytes=${items[1].itemJson.toByteArray(Charsets.UTF_8).size}"), warnings)
            assertEquals(1L, h.stats.oversized.get())
            assertEquals(2L, h.stats.duplicate.get())
            assertEquals(0L, h.stats.badRequest.get())
            assertEquals(0L, h.stats.uploadFailures.get())
            assertTrue(h.queued.isEmpty())
            assertNull(h.pending)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `413 splits maximum batch down to singletons within seven levels`() = runTest {
        val h = Harness()
        h.initialize()
        h.queueBatch(100)
        h.api.uploads.addAll(List(199) { UploadResult.PayloadTooLarge })
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(199, h.api.batches.size)
        assertEquals(100, h.api.bodies.count { it.size == 1 })
        assertEquals(100L, h.stats.oversized.get())
        assertEquals(0L, h.stats.uploadFailures.get())
        assertTrue(h.queued.isEmpty())
        assertNull(h.pending)
    }

    @Test fun `failed split half is persisted and retried before its sibling and new arrivals`() = runTest {
        val h = Harness()
        h.initialize()
        val items = h.queueBatch(4)
        val first = items.take(2)
        val second = items.drop(2)
        h.api.uploads.addAll(listOf(UploadResult.PayloadTooLarge, UploadResult.TransportFailure("IOException")))
        assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
        assertEquals(CensusSpool.InFlight(CensusApi.batchId(first.map { it.fingerprint }), first.map { it.id }), h.pending)
        val fresh = h.queueBatch(1)
        h.queued.clear()
        h.queued += items + fresh
        h.api.uploads.addAll(listOf(UploadResult.Duplicate, UploadResult.Duplicate))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(listOf(items, first, first, second + fresh).map { batch -> batch.map { it.itemJson } }, h.api.bodies)
        assertEquals(h.api.batches[1], h.api.batches[2])
        assertTrue(h.queued.isEmpty())
        assertNull(h.pending)
    }

    @Test fun `bad request removes whole batch counts once warns once and continues`() = runTest {
        val h = Harness()
        h.initialize()
        h.queueBatch(3)
        h.queue()
        val warnings = mutableListOf<String>()
        val tree = warningTree(warnings)
        Timber.plant(tree)
        try {
            h.api.uploads.addAll(listOf(UploadResult.BadRequest, UploadResult.Duplicate))
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(2, h.api.batches.size)
            assertEquals(1L, h.stats.badRequest.get())
            assertEquals(0L, h.stats.oversized.get())
            assertEquals(0L, h.stats.uploadFailures.get())
            assertEquals(listOf("census batch bad_request items=3"), warnings)
            assertTrue(h.queued.isEmpty())
            assertNull(h.pending)
            verify(h.spool, times(2)).clearInFlight()
        } finally {
            Timber.uproot(tree)
        }
    }

    private fun warningTree(warnings: MutableList<String>): Timber.Tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == Log.WARN && tag == "Census") warnings += message
        }
    }
}
