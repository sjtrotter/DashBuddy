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
import cloud.trotter.dashbuddy.core.data.census.CensusUploadLock
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.dashbuddy.core.network.census.CensusApiFactory
import cloud.trotter.dashbuddy.core.network.census.CensusApi
import cloud.trotter.dashbuddy.core.network.census.CensusBudget
import cloud.trotter.dashbuddy.core.network.census.CensusTransport
import cloud.trotter.dashbuddy.core.network.census.EnrolResult
import cloud.trotter.dashbuddy.core.network.census.PolicyResult
import cloud.trotter.dashbuddy.core.network.census.UploadResult
import cloud.trotter.dashbuddy.domain.census.CensusLastRun
import cloud.trotter.dashbuddy.domain.census.CensusRunOutcome
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class CensusUploadWorkerTest {
    @Before fun resetProcessWarnings() {
        CensusUploadWorker.enrolRejectedWarned.set(false)
        CensusUploadWorker.unauthorizedWarnedFor.set(null)
    }

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
        override fun enqueueNow(replaceQueued: Boolean) = Unit
        override fun enqueueSoon() = Unit
        override fun deferUntil(epochMillis: Long) { deadline = epochMillis }
    }

    private class Harness {
        private val memory = MemoryPreferences()
        val preferences = DevSettingsRepository(DevSettingsDataSource(memory), true, Dispatchers.Unconfined)
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

        /** Flip consent from inside a non-suspending transport hook (the in-memory store completes synchronously). */
        fun setConsentSync(enabled: Boolean) {
            memory.data.value = memory.data.value.toMutablePreferences().apply {
                this[androidx.datastore.preferences.core.booleanPreferencesKey("census_upload_enabled")] = enabled
            }
        }

        fun queue(count: Int = 1) {
            repeat(count) { queueBatch(1) }
        }

        fun queueBatch(count: Int, day: LocalDate = LocalDate.now(ZoneOffset.UTC)): List<CensusSpool.Spooled> {
            val items = List(count) {
                val index = ++sequence
                CensusSpool.Spooled("1-$index.json", index.toString().padStart(64, '0'), "{\"item\":$index,\"day\":\"$day\"}")
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
        assertNull(h.preferences.censusLastRun.first()) // A consent-off no-op never overwrites the last real outcome.
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
        assertEquals(emptyMap<String, Int>(), h.preferences.censusPolicy.first())
    }

    @Test fun `install conflict mints only once more and enrol unauthorized stops`() = runTest {
        val h = Harness()
        h.initialize()
        whenever(h.credentials.current()).thenReturn(null)
        val pending = h.credential.copy(enrolled = false)
        val replacement = pending.copy(installId = "87654321-1234-4123-8123-123456789abc")
        whenever(h.credentials.mint()).thenReturn(pending, replacement)
        h.api.enrollments += EnrolResult.InstallExists
        h.api.enrollments += EnrolResult.Unauthorized
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(1L, h.stats.enrolRejected.get())
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
        assertEquals(CensusRunOutcome.ENROL_CONFLICT, h.preferences.censusLastRun.first()?.outcome)
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
        // Nothing queued: the fresh identity stays visible as `enrolled` instead of degrading to `spool_empty`.
        assertEquals(CensusRunOutcome.ENROLLED, h.preferences.censusLastRun.first()?.outcome)
    }

    @Test fun `transient credential failure retries with one INFO counter and no identity changes`() = runTest {
        val h = Harness()
        h.initialize()
        whenever(h.credentials.current()).thenThrow(CensusCredentialStore.Transient())
        val logs = mutableListOf<Pair<Int, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "Census") logs += priority to message
            }
        }
        Timber.plant(tree)
        try {
            assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
            assertEquals(listOf(Log.INFO to "census keystoreTransient=1"), logs)
            assertEquals(1L, h.stats.keystoreTransient.get())
            assertEquals(0L, h.stats.uploadFailures.get())
            verify(h.credentials, never()).pending()
            verify(h.credentials, never()).mint()
            verify(h.credentials, never()).wipe()
            verify(h.credentials, never()).markEnrolled()
            verify(h.spool, never()).take(any(), any())
            assertTrue(h.api.enrolledIds.isEmpty())
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `enrol rejection stops counts each run and warns only once per process`() = runTest {
        val warnings = mutableListOf<String>()
        val tree = warningTree(warnings)
        Timber.plant(tree)
        try {
            for (rejection in listOf(EnrolResult.RequestRejected(400), EnrolResult.Unauthorized)) {
                val h = Harness()
                h.initialize()
                whenever(h.credentials.current()).thenReturn(null)
                whenever(h.credentials.pending()).thenReturn(h.credential.copy(enrolled = false))
                h.api.enrollments.addAll(listOf(rejection, rejection))
                repeat(2) { assertEquals(ListenableWorker.Result.success(), h.worker().doWork()) }
                val lastRun = h.preferences.censusLastRun.first()
                assertEquals(CensusRunOutcome.ENROL_REJECTED, lastRun?.outcome)
                assertEquals(if (rejection is EnrolResult.RequestRejected) rejection.status else 401, lastRun?.detail)
                assertEquals(2L, h.stats.enrolRejected.get())
                assertEquals(0L, h.stats.uploadFailures.get())
                verify(h.credentials, never()).mint()
                verify(h.credentials, never()).markEnrolled()
                verify(h.spool, never()).take(any(), any())
            }
            assertEquals(listOf("census enrol rejected status=400"), warnings)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `enrol persists only numeric policy projection`() = runTest {
        val h = Harness()
        h.initialize()
        whenever(h.credentials.current()).thenReturn(null)
        whenever(h.credentials.pending()).thenReturn(h.credential.copy(enrolled = false))
        h.api.enrollments += EnrolResult.Enrolled(Json.parseToJsonElement(
            """{"dailySkeletonBudget":300,"maxBatchItems":100,"maxBatchBytes":921600,"maxSkeletonBytes":65536,"k":10,"extra":"discard"}""",
        ).jsonObject)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(mapOf("dailySkeletonBudget" to 300, "maxBatchItems" to 100,
            "maxBatchBytes" to 921600, "maxSkeletonBytes" to 65536, "k" to 10), h.preferences.censusPolicy.first())
    }

    @Test fun `thirty stale items are removed locally and seventy fresh items upload`() = runTest {
        val h = Harness()
        h.initialize()
        val today = LocalDate.now(ZoneOffset.UTC)
        val stale = h.queueBatch(30, today.minusDays(8))
        val fresh = h.queueBatch(70, today.minusDays(7)) // Seven days old is still accepted.
        h.queued.clear()
        h.queued += stale + fresh
        h.api.uploads += UploadResult.Accepted(70, 0, emptyMap(), CensusBudget(230, 1000, 39, 300))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        verify(h.spool).remove(stale.map { it.id })
        assertEquals(listOf(fresh.map { it.itemJson }), h.api.bodies)
        assertEquals(listOf(CensusApi.batchId(fresh.map { it.fingerprint })), h.api.batches)
        assertEquals(30L, h.stats.stale.get())
        assertEquals(70L, h.stats.uploaded.get())
        assertTrue(h.queued.isEmpty())
    }

    @Test fun `stale in flight items invalidate batch id and entirely stale batch does not stop fresh work`() = runTest {
        val h = Harness()
        h.initialize()
        val stale = h.queueBatch(2, LocalDate.now(ZoneOffset.UTC).minusDays(8))
        val fresh = h.queueBatch(1)
        h.pending = CensusSpool.InFlight("expired-batch", stale.map { it.id })
        h.api.uploads += UploadResult.Duplicate
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(listOf(fresh.map { it.itemJson }), h.api.bodies)
        assertEquals(listOf(CensusApi.batchId(fresh.map { it.fingerprint })), h.api.batches)
        assertEquals(2L, h.stats.stale.get())
        assertTrue(h.queued.isEmpty())
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
        val lastRun = requireNotNull(h.preferences.censusLastRun.first())
        assertEquals(CensusLastRun(lastRun.atMillis, CensusRunOutcome.UPLOADED, 1), lastRun)
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
            assertEquals(CensusRunOutcome.DEFERRED, h.preferences.censusLastRun.first()?.outcome)
            assertTrue(h.scheduler.deadline > System.currentTimeMillis())
            assertEquals(h.scheduler.deadline, h.preferences.nextAllowedAtMillis.first())
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(CensusRunOutcome.DEFERRED, h.preferences.censusLastRun.first()?.outcome)
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

    @Test fun `year long retry after is capped and consent toggle allows upload now`() = runTest {
        for (enrolling in listOf(false, true)) {
            val h = Harness()
            h.initialize()
            h.queue()
            if (enrolling) {
                whenever(h.credentials.current()).thenReturn(null)
                whenever(h.credentials.pending()).thenReturn(h.credential.copy(enrolled = false))
                h.api.enrollments += EnrolResult.RateLimited(31_536_000)
            } else {
                h.api.uploads += UploadResult.RateLimited(31_536_000)
            }
            val before = System.currentTimeMillis()
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertTrue(h.scheduler.deadline >= before + 86_400_000)
            assertTrue(h.scheduler.deadline <= System.currentTimeMillis() + 86_460_000)
            h.preferences.setCensusUploadEnabled(false)
            assertEquals(0L, h.preferences.nextAllowedAtMillis.first())
            h.preferences.setNextAllowedAtMillis(Long.MAX_VALUE)
            h.preferences.setCensusUploadEnabled(true)
            assertEquals(0L, h.preferences.nextAllowedAtMillis.first())
            if (enrolling) h.api.enrollments += EnrolResult.Enrolled(JsonObject(emptyMap()))
            h.api.uploads += UploadResult.Duplicate
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
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

    @Test fun `unknown enrolled identity warns once per process and never remints`() = runTest {
        val warnings = mutableListOf<String>()
        val tree = warningTree(warnings)
        Timber.plant(tree)
        try {
            repeat(2) {
                val h = Harness()
                h.initialize()
                h.queue()
                h.api.uploads.addAll(List(2) { UploadResult.Unauthorized(System.currentTimeMillis()) })
                assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
                assertEquals(2, h.api.batches.size)
                assertEquals(CensusRunOutcome.UNAUTHORIZED, h.preferences.censusLastRun.first()?.outcome)
                assertEquals(1L, h.stats.unauthorized.get())
                assertEquals(1, h.queued.size)
                verify(h.credentials, never()).mint()
                verify(h.credentials, never()).wipe()
                assertTrue(h.api.enrolledIds.isEmpty())
            }
            assertEquals(listOf("census unauthorized: identity unknown to server"), warnings)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `unknown identity warns again after the identity changes`() = runTest {
        val warnings = mutableListOf<String>()
        val tree = warningTree(warnings)
        Timber.plant(tree)
        try {
            val first = Harness()
            first.initialize()
            first.queue()
            first.api.uploads.addAll(List(2) { UploadResult.Unauthorized(System.currentTimeMillis()) })
            assertEquals(ListenableWorker.Result.success(), first.worker().doWork())

            val second = Harness()
            second.initialize()
            val replacement = second.credential.copy(installId = "87654321-1234-4123-8123-123456789abc")
            whenever(second.credentials.current()).thenReturn(replacement)
            second.queue()
            second.api.uploads.addAll(List(2) { UploadResult.Unauthorized(System.currentTimeMillis()) })
            assertEquals(ListenableWorker.Result.success(), second.worker().doWork())
            assertEquals(List(2) { "census unauthorized: identity unknown to server" }, warnings)

            second.api.uploads.addAll(List(2) { UploadResult.Unauthorized(System.currentTimeMillis()) })
            assertEquals(ListenableWorker.Result.success(), second.worker().doWork())
            assertEquals(List(2) { "census unauthorized: identity unknown to server" }, warnings)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `three all-stale batches with fresh work remaining record stale_removed never spool_empty`() = runTest {
        val h = Harness()
        h.initialize()
        val today = LocalDate.now(ZoneOffset.UTC)
        repeat(3) { h.queueBatch(100, today.minusDays(9)) }
        val fresh = h.queueBatch(1)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertTrue(h.api.batches.isEmpty())
        assertEquals(300L, h.stats.stale.get())
        assertEquals(listOf(fresh), h.queued)
        val lastRun = requireNotNull(h.preferences.censusLastRun.first())
        assertEquals(CensusLastRun(lastRun.atMillis, CensusRunOutcome.STALE_REMOVED, 300), lastRun)
    }

    @Test fun `a 401 after consent is switched off records disabled not unauthorized`() = runTest {
        val h = Harness()
        h.initialize()
        h.queue()
        h.api.uploads += UploadResult.Unauthorized(System.currentTimeMillis())
        val before = h.api.beforeUpload
        h.api.beforeUpload = { batchId, json ->
            before(batchId, json)
            h.setConsentSync(false)
        }
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(1, h.api.batches.size) // No re-sign once consent is off.
        assertEquals(0L, h.stats.unauthorized.get())
        assertNull(h.preferences.censusLastRun.first()) // The consent-off stop is a no-op, not an identity verdict.
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
            assertEquals(if (skew) 0L else 1L, h.stats.unauthorized.get())
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
