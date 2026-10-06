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
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.core.data.census.HealthLedgerStore
import cloud.trotter.dashbuddy.core.data.census.PersistentHealthSink
import cloud.trotter.dashbuddy.domain.capture.CensusEnvelopeSink
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.census.HealthKey
import cloud.trotter.dashbuddy.domain.census.HealthLedger
import cloud.trotter.dashbuddy.domain.census.HealthRow
import cloud.trotter.dashbuddy.core.network.census.HealthResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
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
import cloud.trotter.dashbuddy.domain.census.EnvelopeProjection
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
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
    private data class LogEntry(val priority: Int, val tag: String?, val message: String)

    private val logs = mutableListOf<LogEntry>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += LogEntry(priority, tag, message)
        }
    }

    @Before fun plant() = Timber.plant(tree)

    @After fun uproot() = Timber.uproot(tree)

    private fun deferrals(): List<LogEntry> = logs.filter { it.message.startsWith("census deferred") }

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
        val envelopeResults = ArrayDeque<UploadResult>()
        val envelopeBodies = mutableListOf<List<String>>()
        val envelopeBatches = mutableListOf<String>()
        val stages = mutableListOf<String>()
        var beforeEnvelope: (String, List<String>) -> Unit = { _, _ -> }
        override suspend fun uploadEnvelopes(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult {
            beforeEnvelope(batchId, itemsJson)
            stages += "envelopes"
            envelopeBodies += itemsJson
            envelopeBatches += batchId
            return envelopeResults.removeFirst()
        }
        val healthResults = ArrayDeque<HealthResult>()
        val healthBodies = mutableListOf<List<String>>()
        var beforeHealth: suspend () -> Unit = {}
        override suspend fun postHealth(cred: Bearer.Credential, reportsJson: List<String>): HealthResult {
            healthBodies += reportsJson
            beforeHealth()
            return healthResults.removeFirst()
        }
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
            stages += "skeletons"
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

    private class Harness(scope: CoroutineScope, io: CoroutineDispatcher) {
        private val memory = MemoryPreferences()
        val preferences = spy(DevSettingsRepository(DevSettingsDataSource(memory), true, Dispatchers.Unconfined))
        val credentials: CensusCredentialStore = mock()
        val spool: CensusSpool = mock()
        val envelopeSpool: CensusSpool = mock()
        val envelopeSink: CensusEnvelopeSink = mock()
        val envelopes = mutableListOf<CensusSpool.Spooled>()
        var envelopePending: CensusSpool.InFlight? = null
        val api = FakeApi()
        val scheduler = Scheduler()
        val stats = CensusUploadStats()
        val healthMemory = MemoryPreferences()
        val healthStore = HealthLedgerStore(healthMemory, stats)
        val healthSink = PersistentHealthSink(healthStore, stats, scope, io)
        var rulesetReleaseTag: String? = "dev"
        val metadataProvider = object : ReplayMetadataProvider {
            override fun current() = ReplayMetadata(engineVersion = 1, rulesetReleaseTag = rulesetReleaseTag)
        }

        suspend fun health(day: LocalDate = LocalDate.now(ZoneOffset.UTC).minusDays(1), version: String = "7.1"): HealthKey {
            val key = HealthKey(day.toString(), "doordash", version)
            healthSink.apply { it.record(key, "doordash.screen.offer").record(key, null).trip(key) }
            return key
        }
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
            whenever(envelopeSpool.take(20, 900 * 1024)).thenAnswer {
                val retained = envelopePending?.ids?.mapNotNull { id -> envelopes.find { it.id == id } }
                if (!retained.isNullOrEmpty()) retained else { envelopePending = null; envelopes.take(20) }
            }
            whenever(envelopeSpool.inFlight()).thenAnswer { envelopePending }
            whenever(envelopeSpool.markInFlight(any(), any())).thenAnswer {
                envelopePending = CensusSpool.InFlight(it.getArgument(1), it.getArgument<Collection<String>>(0).toList())
                Unit
            }
            whenever(envelopeSpool.remove(any())).thenAnswer {
                val ids = it.getArgument<Collection<String>>(0)
                envelopes.removeAll { item -> item.id in ids }
                Unit
            }
            whenever(envelopeSpool.clearInFlight()).thenAnswer { envelopePending = null; Unit }
            whenever(envelopeSink.invalidate()).thenAnswer { envelopes.clear(); envelopePending = null; Unit }
            api.beforeEnvelope = { batchId, json ->
                val persisted = requireNotNull(envelopePending)
                assertEquals(batchId, persisted.batchId)
                assertEquals(json, persisted.ids.map { id -> envelopes.single { it.id == id }.itemJson })
            }
            api.beforeUpload = { batchId, json ->
                val persisted = requireNotNull(pending)
                assertEquals(batchId, persisted.batchId)
                assertEquals(json, persisted.ids.map { id -> queued.flatten().single { it.id == id }.itemJson })
            }
        }

        fun consentEnabled(): Boolean? = memory.data.value[
            androidx.datastore.preferences.core.booleanPreferencesKey("census_upload_enabled")
        ]

        fun persistedHealth(): HealthLedger = healthMemory.data.value[
            androidx.datastore.preferences.core.stringPreferencesKey("ledger_json")
        ]?.let { Json.decodeFromString<HealthLedger>(it) } ?: HealthLedger()

        /** Flip consent from inside a non-suspending transport hook (the in-memory store completes synchronously). */
        fun setConsentSync(enabled: Boolean) {
            memory.data.value = memory.data.value.toMutablePreferences().apply {
                this[androidx.datastore.preferences.core.booleanPreferencesKey("census_upload_enabled")] = enabled
            }
        }

        fun setShareSync(enabled: Boolean) {
            memory.data.value = memory.data.value.toMutablePreferences().apply {
                this[androidx.datastore.preferences.core.booleanPreferencesKey("census_share_captures")] = enabled
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

        fun queueMixedBatch(): List<CensusSpool.Spooled> {
            val day = LocalDate.now(ZoneOffset.UTC)
            val meta = ReplayMetadata(engineVersion = 1)
            val screen = cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.outcome(
                cloud.trotter.dashbuddy.domain.model.accessibility.UiNode(text = "Continue"), null,
                meta, cloud.trotter.dashbuddy.domain.state.Platform.DoorDash, day,
            ) as cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Outcome.Built
            val notification = cloud.trotter.dashbuddy.core.pipeline.census.NotificationSkeletonBuilder.outcome(
                cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData(
                    "Continue", null, null, null, packageName = cloud.trotter.dashbuddy.domain.state.Platform.DoorDash.packageName!!,
                    postTime = 0, isClearable = true, channelId = "synthetic",
                ), meta, cloud.trotter.dashbuddy.domain.state.Platform.DoorDash, day,
            ) as cloud.trotter.dashbuddy.core.pipeline.census.NotificationSkeletonBuilder.Outcome.Built
            return listOf(screen.skeleton.fingerprint to screen.json, notification.skeleton.fingerprint to notification.json)
                .map { (fingerprint, json) -> CensusSpool.Spooled("1-${++sequence}.json", fingerprint, json) }
                .also { queued += it }
        }

        suspend fun queueEnvelopes(count: Int = 1): List<CensusSpool.Spooled> {
            preferences.setCensusShareCaptures(true)
            return List(count) {
                val index = ++sequence
                val fingerprint = index.toString().padStart(64, '0')
                val json = requireNotNull(EnvelopeProjection.project(
                    """{"schemaId":"uinode.v1","timestamp":7200123,"platform":"doordash","metadata":{"deviceFingerprint":"removed","rulesetSignature":"removed"},"payload":{"text":"Continue $index"}}""", fingerprint))
                CensusSpool.Spooled("2-$index.json", fingerprint, json)
            }.also { envelopes += it }
        }

        fun worker(): CensusUploadWorker = TestListenableWorkerBuilder<CensusUploadWorker>(RuntimeEnvironment.getApplication())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    CensusUploadWorker(appContext, workerParameters, preferences, credentials, spool, factory, scheduler, stats, lock, healthSink, metadataProvider, envelopeSpool, envelopeSink)
            }).build()
    }

    private fun TestScope.harness() = Harness(backgroundScope, StandardTestDispatcher(testScheduler))

    @Test fun `disabled does not create API read credentials or drain spool`() = runTest {
        val h = harness()
        h.initialize(enabled = false)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertNull(h.preferences.censusLastRun.first()) // A consent-off no-op never overwrites the last real outcome.
        assertEquals(0, h.factoryCalls)
        verify(h.credentials, never()).current()
        verify(h.spool, never()).take(any(), any())
    }

    @Test fun `fresh enrollment is persisted before upload and lost replies reuse pending identity`() = runTest {
        val h = harness()
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
        val h = harness()
        h.initialize()
        whenever(h.credentials.current()).thenReturn(null)
        h.queueEnvelopes()
        val pending = h.credential.copy(enrolled = false)
        val replacement = pending.copy(installId = "87654321-1234-4123-8123-123456789abc")
        h.health()
        var mints = 0
        whenever(h.credentials.mint()).thenAnswer {
            mints++
            assertEquals(if (mints == 1) 0L else 1L, h.persistedHealth().generation)
            if (mints == 2) assertTrue(h.persistedHealth().rows.isEmpty())
            if (mints == 1) pending else replacement
        }
        h.api.enrollments += EnrolResult.InstallExists
        h.api.enrollments += EnrolResult.Unauthorized
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(1L, h.stats.enrolRejected.get())
        verify(h.credentials, times(2)).mint()
        verify(h.envelopeSink).invalidate()
        verify(h.preferences).setCensusShareCaptures(false)
        assertFalse(h.preferences.censusShareCaptures.first())
        assertNotEquals(h.api.enrolledIds[0], h.api.enrolledIds[1])
        verify(h.credentials, never()).markEnrolled()
    }

    @Test fun `second install conflict stops without another mint`() = runTest {
        val h = harness()
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
        val h = harness()
        h.initialize()
        whenever(h.credentials.current()).thenThrow(CensusCredentialStore.Unusable())
        h.queueEnvelopes()
        h.health()
        whenever(h.credentials.mint()).thenAnswer {
            assertEquals(HealthLedger(generation = 1), h.persistedHealth())
            h.credential.copy(enrolled = false)
        }
        h.api.enrollments += EnrolResult.Enrolled(JsonObject(emptyMap()))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        verify(h.credentials).mint()
        verify(h.credentials).markEnrolled()
        verify(h.envelopeSink).invalidate()
        verify(h.preferences).setCensusShareCaptures(false)
        assertFalse(h.preferences.censusShareCaptures.first())
        assertTrue(h.envelopes.isEmpty())
        // Nothing queued: the fresh identity stays visible as `enrolled` instead of degrading to `spool_empty`.
        assertEquals(CensusRunOutcome.ENROLLED, h.preferences.censusLastRun.first()?.outcome)
    }

    @Test fun `transient credential failure retries with one INFO counter and no identity changes`() = runTest {
        val h = harness()
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
                val h = harness()
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
        val h = harness()
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
        val h = harness()
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
        val h = harness()
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
        val h = harness()
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
            logs.clear()
            val h = harness()
            h.initialize()
            h.queue()
            h.api.uploads += result
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            val cause = if (result is UploadResult.BudgetExhausted) "budget" else "upload_rate_limit"
            assertEquals(listOf(LogEntry(Log.INFO, "Census", "census deferred cause=$cause seconds=3600")), deferrals())
            assertEquals(CensusRunOutcome.DEFERRED, h.preferences.censusLastRun.first()?.outcome)
            val deadline = h.preferences.nextAllowedAtMillis.first()
            assertTrue(deadline > System.currentTimeMillis())
            assertEquals(0L, h.scheduler.deadline)
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(CensusRunOutcome.DEFERRED, h.preferences.censusLastRun.first()?.outcome)
            assertEquals(2, h.factoryCalls)
            assertEquals(deadline, h.preferences.nextAllowedAtMillis.first())
            assertEquals(0L, h.scheduler.deadline)
            assertEquals(1, h.api.batches.size)
            assertEquals(1, h.queued.size)
            h.preferences.setNextAllowedAtMillis(System.currentTimeMillis() - 1)
            h.api.uploads += UploadResult.Duplicate
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(3, h.factoryCalls)
            assertTrue(h.queued.isEmpty())
        }
    }

    @Test fun `year long retry after is capped and consent toggle allows upload now`() = runTest {
        for (enrolling in listOf(false, true)) {
            logs.clear()
            val h = harness()
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
            val cause = if (enrolling) "enrol_rate_limit" else "upload_rate_limit"
            assertEquals(listOf(LogEntry(Log.INFO, "Census", "census deferred cause=$cause seconds=86400")), deferrals())
            assertTrue(h.preferences.nextAllowedAtMillis.first() >= before + 86_400_000)
            assertTrue(h.preferences.nextAllowedAtMillis.first() <= System.currentTimeMillis() + 86_460_000)
            assertEquals(0L, h.scheduler.deadline)
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

    @Test fun `a pending identity waits out its enrol 429 on later runs without re-enrolling`() = runTest {
        val h = harness()
        h.initialize()
        h.queue()
        whenever(h.credentials.current()).thenReturn(null)
        whenever(h.credentials.pending()).thenReturn(h.credential.copy(enrolled = false))
        h.api.enrollments += EnrolResult.RateLimited(3600)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(listOf(LogEntry(Log.INFO, "Census", "census deferred cause=enrol_rate_limit seconds=3600")), deferrals())
        assertEquals(CensusRunOutcome.DEFERRED, h.preferences.censusLastRun.first()?.outcome)
        val deadline = h.preferences.nextAllowedAtMillis.first()
        assertTrue(deadline > System.currentTimeMillis())
        assertEquals(1, h.api.enrolledIds.size)
        // The hourly cadence is never postponed, so the next run arrives inside the window: no enrol call.
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(CensusRunOutcome.DEFERRED, h.preferences.censusLastRun.first()?.outcome)
        assertEquals(1, h.api.enrolledIds.size)
        assertEquals(deadline, h.preferences.nextAllowedAtMillis.first())
        assertEquals(0L, h.scheduler.deadline)
        h.preferences.setNextAllowedAtMillis(System.currentTimeMillis() - 1)
        h.api.enrollments += EnrolResult.Enrolled(JsonObject(emptyMap()))
        h.api.uploads += UploadResult.Duplicate
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(2, h.api.enrolledIds.size)
        assertTrue(h.queued.isEmpty())
    }

    @Test fun `a pending enrolment under a stored deadline logs the recheck cause`() = runTest {
        val h = harness()
        h.initialize()
        h.queue()
        whenever(h.credentials.pending()).thenReturn(h.credential.copy(enrolled = false))
        h.preferences.setNextAllowedAtMillis(System.currentTimeMillis() + 3_600_000)
        logs.clear()
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        val entry = deferrals().single()
        assertTrue(entry.message, entry.message.matches(Regex("census deferred cause=stored_deadline remaining=[0-9]+")))
        assertEquals(CensusRunOutcome.DEFERRED, h.preferences.censusLastRun.first()?.outcome)
        assertTrue(h.api.bodies.isEmpty())
    }

    @Test fun `stored deadline logs remaining seconds without uploading`() = runTest {
        val h = harness()
        h.initialize()
        h.queue()
        val deadline = System.currentTimeMillis() + 3_600_000
        h.preferences.setNextAllowedAtMillis(deadline)
        val before = System.currentTimeMillis()
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        val after = System.currentTimeMillis()
        val entry = deferrals().single()
        assertEquals(Log.INFO, entry.priority)
        assertEquals("Census", entry.tag)
        assertTrue(entry.message.matches(Regex("census deferred cause=stored_deadline remaining=[0-9]+")))
        val remainingSeconds = entry.message.substringAfter("remaining=").toLong()
        assertTrue(remainingSeconds in ((deadline - after) / 1000).coerceAtLeast(0)..((deadline - before) / 1000).coerceAtLeast(0))
        assertEquals("deferred $remainingSeconds", h.preferences.censusLastRun.first()?.token())
        assertEquals(deadline, h.preferences.nextAllowedAtMillis.first())
        assertTrue(h.api.bodies.isEmpty())
        assertEquals(1, h.queued.size)
    }

    @Test fun `unauthorized resigns once with same batch and bytes`() = runTest {
        val h = harness()
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
        val h = harness()
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
                val h = harness()
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
            val first = harness()
            first.initialize()
            first.queue()
            first.api.uploads.addAll(List(2) { UploadResult.Unauthorized(System.currentTimeMillis()) })
            assertEquals(ListenableWorker.Result.success(), first.worker().doWork())

            val second = harness()
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
        val h = harness()
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
        val h = harness()
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
        val h = harness()
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
            val h = harness()
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
        val h = harness()
        h.initialize()
        h.health(LocalDate.now(ZoneOffset.UTC))
        h.queue()
        whenever(h.credentials.wipe()).thenAnswer {
            assertEquals(HealthLedger(generation = 1), h.persistedHealth())
            assertEquals(false, h.consentEnabled())
            Unit
        }
        h.api.uploads += UploadResult.Revoked
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        verify(h.envelopeSink).invalidate()
        verify(h.credentials).wipe()
        assertFalse(h.preferences.censusUploadEnabled.first())
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(1, h.api.batches.size)
    }

    @Test fun `server and transport failures retry without removing records`() = runTest {
        for (result in listOf(UploadResult.ServerUnavailable(503), UploadResult.TransportFailure("IOException"))) {
            val h = harness()
            h.initialize()
            h.queue()
            h.api.uploads += result
            assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
            assertEquals(1, h.queued.size)
            assertEquals(1L, h.stats.uploadFailures.get())
        }
    }

    @Test fun `transport failure retries mixed batch before new arrivals with original batch id`() = runTest {
        val h = harness()
        h.initialize()
        val a = h.queueMixedBatch()
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
        val h = harness()
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
        val h = harness()
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
        val h = harness()
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
        val h = harness()
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

    @Test fun `closed day posts exact singleton JSON and acknowledges once while today stays open`() = runTest {
        val h = harness()
        h.initialize()
        val key = h.health()
        h.health(LocalDate.now(ZoneOffset.UTC))
        h.api.healthResults += HealthResult.Accepted(1, emptyMap())
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        val expected = """{"day":"${key.day}","platform":"doordash","platformAppVersion":"7.1","appVersion":"${BuildConfig.VERSION_NAME}","rulesetVersion":"dev","admitted":1,"unknown":1,"trips":1,"ruleCounts":{"doordash.screen.offer":1}}"""
        assertEquals(listOf(listOf(expected)), h.api.healthBodies)
        assertEquals("health_posted 1", h.preferences.censusLastRun.first()?.token())
        assertEquals(1L, h.stats.healthPosted.get())
        val row = h.healthStore.load().rows.getValue(key.toString())
        assertEquals(row.revision, row.postedRevision)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(1, h.api.healthBodies.size)
        assertEquals(CensusRunOutcome.SPOOL_EMPTY, h.preferences.censusLastRun.first()?.outcome)
    }

    @Test fun `422 refuses only current revision and a later frame posts again`() = runTest {
        val h = harness()
        h.initialize()
        val key = h.health()
        h.api.healthResults += HealthResult.BatchQuality(mapOf("bad_version" to 1))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals("health_rejected 1", h.preferences.censusLastRun.first()?.token())
        assertEquals(1L, h.stats.healthRejected.get())
        val refused = h.healthStore.load().rows.getValue(key.toString())
        assertEquals(refused.revision, refused.refusedRevision)
        h.worker().doWork()
        assertEquals(1, h.api.healthBodies.size)
        h.healthSink.apply { it.record(key, "doordash.screen.offer") }
        h.api.healthResults += HealthResult.Accepted(1, emptyMap())
        h.worker().doWork()
        assertEquals(2, h.api.healthBodies.size)
        assertEquals("health_posted 1", h.preferences.censusLastRun.first()?.token())
    }

    @Test fun `accepted zero bad request and payload too large refuse and count reports`() = runTest {
        for (result in listOf(HealthResult.Accepted(0, mapOf("bad_day" to 1)), HealthResult.BadRequest, HealthResult.PayloadTooLarge)) {
            val h = harness()
            h.initialize()
            val key = h.health()
            h.api.healthResults += result
            h.worker().doWork()
            val row = h.healthStore.load().rows.getValue(key.toString())
            assertEquals(row.revision, row.refusedRevision)
            assertEquals(1L, h.stats.healthRejected.get())
            assertEquals("health_rejected 1", h.preferences.censusLastRun.first()?.token())
        }
    }

    @Test fun `changed row during post is not acknowledged by an older receipt`() = runTest {
        for (result in listOf(HealthResult.Accepted(1, emptyMap()), HealthResult.BatchQuality(mapOf("bad_day" to 1)))) {
            val h = harness()
            h.initialize()
            val key = h.health()
            h.api.beforeHealth = { h.healthSink.apply { it.record(key, null) } }
            h.api.healthResults += result
            h.worker().doWork()
            val row = h.healthStore.load().rows.getValue(key.toString())
            assertEquals(-1L, row.postedRevision)
            assertEquals(-1L, row.refusedRevision)
            h.api.beforeHealth = {}
            h.api.healthResults += HealthResult.Accepted(1, emptyMap())
            h.worker().doWork()
            assertEquals(2, h.api.healthBodies.size)
        }
    }

    @Test fun `oversized report is refused locally with its complete rule map intact`() = runTest {
        val h = harness()
        h.initialize()
        val key = h.health()
        val rules = (1..600).associate { "a.r$it" to 1 }
        h.healthSink.apply { it.copy(rows = mapOf(key.toString() to HealthRow(admitted = 600, ruleCounts = rules, revision = 9))) }
        val warnings = mutableListOf<String>()
        val tree = warningTree(warnings)
        Timber.plant(tree)
        try {
            h.worker().doWork()
            h.worker().doWork()
            assertTrue(h.api.healthBodies.isEmpty())
            assertEquals(1L, h.stats.healthOversized.get())
            val row = h.healthStore.load().rows.getValue(key.toString())
            assertEquals(9L, row.refusedRevision)
            assertEquals(rules, row.ruleCounts)
            assertEquals(listOf("census health oversized day=${key.day}"), warnings)
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `health 429 ends the run without uploading skeletons or changing their deadline`() = runTest {
        for (result in listOf(HealthResult.RateLimited(3600), HealthResult.BudgetExhausted(3600))) {
            logs.clear()
            val h = harness()
            h.initialize()
            val key = h.health()
            h.health(version = "7.2")
            h.queue()
            h.api.healthResults += result
            h.api.uploads += UploadResult.Duplicate
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            val cause = if (result is HealthResult.BudgetExhausted) "health_budget" else "health_rate_limit"
            assertEquals(listOf(LogEntry(Log.INFO, "Census", "census deferred cause=$cause seconds=3600")), deferrals())
            assertEquals(1, h.api.healthBodies.size)
            assertTrue(h.api.bodies.isEmpty())
            assertEquals(1, h.queued.size)
            assertEquals(0L, h.preferences.nextAllowedAtMillis.first())
            assertEquals(0L, h.scheduler.deadline)
            assertEquals("deferred 3600", h.preferences.censusLastRun.first()?.token())
            assertEquals(-1L, h.healthStore.load().rows.getValue(key.toString()).refusedRevision)
            h.api.healthResults.addAll(List(2) { HealthResult.Accepted(1, emptyMap()) })
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(3, h.api.healthBodies.size)
            assertEquals(1, h.api.bodies.size)
            assertEquals(0L, h.preferences.nextAllowedAtMillis.first())
            assertEquals(2L, h.stats.healthPosted.get())
        }
    }

    @Test fun `health 429 clamps the run detail without scheduling a skeleton deferral`() = runTest {
        for (seconds in listOf(0L, Long.MAX_VALUE)) {
            for (result in listOf(HealthResult.RateLimited(seconds), HealthResult.BudgetExhausted(seconds))) {
                val h = harness()
                h.initialize()
                h.health()
                h.api.healthResults += result
                assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
                assertEquals("deferred ${seconds.coerceIn(1, 86_400)}", h.preferences.censusLastRun.first()?.token())
                assertEquals(0L, h.preferences.nextAllowedAtMillis.first())
                assertEquals(0L, h.scheduler.deadline)
            }
        }
    }

    @Test fun `health outcomes survive a live skeleton deadline`() = runTest {
        for ((result, token) in listOf(HealthResult.Accepted(1, emptyMap()) to "health_posted 1",
            HealthResult.BatchQuality(mapOf("bad_count" to 1)) to "health_rejected 1")) {
            val h = harness()
            h.initialize()
            h.health()
            h.queue()
            val deadline = System.currentTimeMillis() + 3_600_000
            h.preferences.setNextAllowedAtMillis(deadline)
            h.api.healthResults += result
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(token, h.preferences.censusLastRun.first()?.token())
            assertTrue(h.api.bodies.isEmpty())
            assertEquals(deadline, h.preferences.nextAllowedAtMillis.first())
            assertEquals(0L, h.scheduler.deadline)
        }
    }

    @Test fun `a closed health day posts on a run whose skeleton stage is deferred`() = runTest {
        val h = harness()
        h.initialize()
        val today = LocalDate.now(ZoneOffset.UTC)
        for (days in 1L..4L) h.health(today.minusDays(days))
        h.queue()
        h.api.healthResults.addAll(List(4) { HealthResult.Accepted(1, emptyMap()) })
        h.api.uploads += UploadResult.BudgetExhausted(3600)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(3, h.api.healthBodies.size)
        assertEquals(1, h.api.bodies.size)
        val deadline = h.preferences.nextAllowedAtMillis.first()
        assertTrue(deadline > System.currentTimeMillis())
        assertEquals(0L, h.scheduler.deadline)

        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(4, h.api.healthBodies.size)
        val fourth = Json.parseToJsonElement(h.api.healthBodies.last().single()).jsonObject
        assertEquals("\"${today.minusDays(1)}\"", fourth.getValue("day").toString())
        assertEquals("health_posted 1", h.preferences.censusLastRun.first()?.token())
        assertEquals(4L, h.stats.healthPosted.get())
        assertEquals(1, h.api.bodies.size)
        assertEquals(1, h.queued.size)
        assertEquals(deadline, h.preferences.nextAllowedAtMillis.first())
        assertEquals(0L, h.scheduler.deadline)
    }

    @Test fun `health failures with an empty spool remain visible`() = runTest {
        for (result in listOf(HealthResult.Unauthorized(null), HealthResult.ServerUnavailable(503),
            HealthResult.TransportFailure("IOException"))) {
            val h = harness()
            h.initialize()
            val key = h.health()
            h.api.healthResults += result
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals("failure", h.preferences.censusLastRun.first()?.token())
            assertEquals(1L, h.stats.healthFailed.get())
            assertTrue(h.stats.summary().contains(",healthFailed=1"))
            assertEquals(-1L, h.healthStore.load().rows.getValue(key.toString()).refusedRevision)
        }
    }

    @Test fun `health ruleset version follows current metadata with a dev fallback`() = runTest {
        val h = harness()
        h.initialize()
        val key = h.health()
        for (tag in listOf("v1.2.3-rc1", null)) {
            h.rulesetReleaseTag = tag
            h.healthSink.apply { it.record(key, null) }
            h.api.healthResults += HealthResult.Accepted(1, emptyMap())
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            val json = Json.parseToJsonElement(h.api.healthBodies.last().single()).jsonObject
            assertEquals("\"${tag ?: "dev"}\"", json.getValue("rulesetVersion").toString())
        }
    }

    @Test fun `health transient failures do not defer skeletons or refuse rows`() = runTest {
        for (result in listOf(HealthResult.Unauthorized(null), HealthResult.ServerUnavailable(503),
            HealthResult.TransportFailure("IOException"))) {
            val h = harness()
            h.initialize()
            val key = h.health()
            h.health(version = "7.2")
            h.queue()
            h.api.healthResults += result
            h.api.uploads += UploadResult.Duplicate
            h.worker().doWork()
            assertEquals(1, h.api.healthBodies.size)
            assertEquals(1, h.api.bodies.size)
            assertEquals(0L, h.preferences.nextAllowedAtMillis.first())
            assertEquals(-1L, h.healthStore.load().rows.getValue(key.toString()).refusedRevision)
        }
    }

    @Test fun `health revoked wipes credentials and stops before skeletons`() = runTest {
        val h = harness()
        h.initialize()
        h.health()
        h.queue()
        whenever(h.credentials.wipe()).thenAnswer {
            assertEquals(HealthLedger(generation = 1), h.persistedHealth())
            assertEquals(false, h.consentEnabled())
            Unit
        }
        h.api.healthResults += HealthResult.Revoked
        h.worker().doWork()
        verify(h.envelopeSink).invalidate()
        verify(h.credentials).wipe()
        assertFalse(h.preferences.censusUploadEnabled.first())
        assertTrue(h.api.bodies.isEmpty())
        assertEquals(CensusRunOutcome.REVOKED, h.preferences.censusLastRun.first()?.outcome)
    }

    @Test fun `health records locally with consent off but sends no request`() = runTest {
        val h = harness()
        h.initialize(enabled = false)
        h.health()
        h.worker().doWork()
        assertTrue(h.api.healthBodies.isEmpty())
        assertEquals(0, h.factoryCalls)
        assertEquals(1, h.healthStore.load().rows.size)
    }

    @Test fun `health posts oldest eligible days first with at most three singletons`() = runTest {
        val h = harness()
        h.initialize()
        val today = LocalDate.now(ZoneOffset.UTC)
        for (days in listOf(1L, 7L, 8L, 3L, 2L, 0L)) h.health(today.minusDays(days))
        h.api.healthResults.addAll(List(3) { HealthResult.Accepted(1, emptyMap()) })
        h.worker().doWork()
        assertTrue(h.api.healthBodies.all { it.size == 1 })
        assertEquals(listOf(7L, 3L, 2L).map { today.minusDays(it).toString() }, h.api.healthBodies.map {
            Json.parseToJsonElement(it.single()).jsonObject.getValue("day").toString().trim('"')
        })
        assertEquals("health_posted 3", h.preferences.censusLastRun.first()?.token())
    }

    @Test fun `health outcomes beat idle enrolled and posted beats health rejected`() = runTest {
        for (accepted in listOf(false, true)) {
            val h = harness()
            h.initialize()
            whenever(h.credentials.current()).thenReturn(null)
            whenever(h.credentials.pending()).thenReturn(h.credential.copy(enrolled = false))
            h.api.enrollments += EnrolResult.Enrolled(JsonObject(emptyMap()))
            h.health()
            h.api.healthResults += HealthResult.BatchQuality(mapOf("bad_day" to 1))
            if (accepted) {
                h.health(version = "7.2")
                h.api.healthResults += HealthResult.Accepted(1, emptyMap())
            }
            h.worker().doWork()
            assertEquals(if (accepted) "health_posted 1" else "health_rejected 1", h.preferences.censusLastRun.first()?.token())
        }
    }

    @Test fun `skeleton movement takes summary precedence over health`() = runTest {
        val results = listOf(
            UploadResult.Accepted(1, 0, emptyMap(), CensusBudget(1, 1, 1, 1)) to CensusRunOutcome.UPLOADED,
            UploadResult.Duplicate to CensusRunOutcome.DUPLICATE,
            UploadResult.BatchQuality(mapOf("bad_day" to 1)) to CensusRunOutcome.REJECTED,
        )
        for ((result, outcome) in results) {
            val h = harness()
            h.initialize()
            h.health()
            h.queue()
            h.api.healthResults += HealthResult.Accepted(1, emptyMap())
            h.api.uploads += result
            h.worker().doWork()
            assertEquals(outcome, h.preferences.censusLastRun.first()?.outcome)
        }
        val h = harness()
        h.initialize()
        h.health()
        h.queueBatch(1, LocalDate.now(ZoneOffset.UTC).minusDays(8))
        h.api.healthResults += HealthResult.Accepted(1, emptyMap())
        h.worker().doWork()
        assertEquals(CensusRunOutcome.STALE_REMOVED, h.preferences.censusLastRun.first()?.outcome)
        assertEquals(CensusRunOutcome.HEALTH_POSTED, CensusRunOutcome.fromWire("health_posted"))
        assertEquals(CensusRunOutcome.HEALTH_REJECTED, CensusRunOutcome.fromWire("health_rejected"))
    }

    @Test fun `skeleton bad request and oversized outcomes survive successful health`() = runTest {
        for ((result, outcome) in listOf(UploadResult.BadRequest to CensusRunOutcome.BAD_REQUEST,
            UploadResult.PayloadTooLarge to CensusRunOutcome.OVERSIZED)) {
            val h = harness()
            h.initialize()
            h.health()
            h.queue()
            h.api.healthResults += HealthResult.Accepted(1, emptyMap())
            h.api.uploads += result
            h.worker().doWork()
            assertEquals(outcome, h.preferences.censusLastRun.first()?.outcome)
        }
    }

    @Test fun `envelopes post after skeletons with the exact projected strings and accepted skeletons rank first`() = runTest {
        for (skeletonAccepted in listOf(false, true)) {
            val h = harness()
            h.initialize()
            h.queue()
            val items = h.queueEnvelopes(2)
            h.api.uploads += if (skeletonAccepted) UploadResult.Accepted(1, 0, emptyMap(), CensusBudget(1, 1, 1, 1))
                else UploadResult.Duplicate
            h.api.envelopeResults += UploadResult.Accepted(2, 0, emptyMap(), CensusBudget(1, 1, 1, 1))
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(listOf("skeletons", "envelopes"), h.api.stages)
            assertEquals(listOf(items.map { it.itemJson }), h.api.envelopeBodies)
            assertEquals(listOf(CensusApi.envelopeBatchId(items.map { it.itemJson })), h.api.envelopeBatches)
            assertTrue(h.envelopes.isEmpty())
            assertNull(h.envelopePending)
            assertEquals(2L, h.stats.envelopesPosted.get())
            assertEquals(2, h.preferences.censusLastRun.first()?.envelopesPosted)
            assertEquals(if (skeletonAccepted) "uploaded 1" else "envelopes_posted 2", h.preferences.censusLastRun.first()?.token())
        }
    }

    @Test fun `empty skeleton spool permits envelopes but sharing off never reads envelope spool`() = runTest {
        val h = harness()
        h.initialize()
        h.queueEnvelopes()
        h.preferences.setCensusShareCaptures(false)
        h.worker().doWork()
        verify(h.envelopeSpool, never()).take(any(), any())
        assertTrue(h.api.envelopeBodies.isEmpty())
        h.preferences.setCensusShareCaptures(true)
        h.api.envelopeResults += UploadResult.Accepted(1, 0, emptyMap(), CensusBudget(1, 1, 1, 1))
        h.worker().doWork()
        assertEquals("envelopes_posted 1", h.preferences.censusLastRun.first()?.token())
        assertEquals(1, h.api.envelopeBodies.size)
    }

    @Test fun `not trusted switches sharing off clears envelope queue and preserves census consent`() = runTest {
        val h = harness()
        h.initialize()
        h.queueEnvelopes(21)
        h.api.envelopeResults += UploadResult.NotTrusted
        val warnings = mutableListOf<String>()
        val tree = warningTree(warnings)
        Timber.plant(tree)
        try {
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals(listOf("census envelopes not_trusted sharing=off items=20"), warnings)
        } finally {
            Timber.uproot(tree)
        }
        assertFalse(h.preferences.censusShareCaptures.first())
        assertTrue(h.preferences.censusUploadEnabled.first())
        assertTrue(h.envelopes.isEmpty())
        assertNull(h.envelopePending)
        verify(h.envelopeSink).invalidate()
        assertEquals(1L, h.stats.envelopesNotTrusted.get())
        assertEquals("not_trusted", h.preferences.censusLastRun.first()?.token())
        h.worker().doWork()
        assertEquals(1, h.api.envelopeBodies.size)
    }

    @Test fun `envelope 429 stores shared deadline and retry keeps exact membership despite new arrivals`() = runTest {
        for (result in listOf(UploadResult.RateLimited(3600), UploadResult.BudgetExhausted(3600))) {
            val h = harness()
            h.initialize()
            val original = h.queueEnvelopes(2)
            h.api.envelopeResults += result
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertEquals("deferred 3600", h.preferences.censusLastRun.first()?.token())
            assertTrue(h.preferences.nextAllowedAtMillis.first() > System.currentTimeMillis())
            val pending = h.envelopePending
            h.queueEnvelopes()
            h.worker().doWork()
            assertEquals(1, h.api.envelopeBodies.size)
            assertEquals(pending, h.envelopePending)
            h.preferences.setNextAllowedAtMillis(0)
            h.api.envelopeResults += UploadResult.Duplicate
            h.worker().doWork()
            assertEquals(listOf(original.map { it.itemJson }, original.map { it.itemJson }), h.api.envelopeBodies)
            assertEquals(h.api.envelopeBatches[0], h.api.envelopeBatches[1])
            assertEquals(1, h.envelopes.size)
        }
    }

    @Test fun `skeleton deferral transport error and unauthorized stop before envelopes`() = runTest {
        for (result in listOf(UploadResult.RateLimited(60), UploadResult.TransportFailure("IOException"),
            UploadResult.Unauthorized(null))) {
            val h = harness()
            h.initialize()
            h.queue()
            h.queueEnvelopes()
            h.api.uploads.addAll(listOf(result, result))
            h.worker().doWork()
            assertTrue(h.api.envelopeBodies.isEmpty())
            verify(h.envelopeSpool, never()).take(any(), any())
        }
        val h = harness()
        h.initialize()
        h.queueEnvelopes()
        h.health()
        h.api.healthResults += HealthResult.Accepted(1, emptyMap())
        h.preferences.setNextAllowedAtMillis(System.currentTimeMillis() + 60_000)
        h.worker().doWork()
        assertEquals("health_posted 1", h.preferences.censusLastRun.first()?.token())
        verify(h.envelopeSpool, never()).take(any(), any())
    }

    @Test fun `envelope split keeps prefix resolves both halves and counts quality rejection`() = runTest {
        val h = harness()
        h.initialize()
        val items = h.queueEnvelopes(4)
        h.api.envelopeResults.addAll(listOf(UploadResult.PayloadTooLarge,
            UploadResult.Accepted(2, 0, emptyMap(), CensusBudget(1, 1, 1, 1)),
            UploadResult.BatchQuality(mapOf("bad_item" to 2))))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(listOf(items, items.take(2), items.drop(2)).map { it.map { item -> item.itemJson } }, h.api.envelopeBodies)
        assertTrue(h.api.envelopeBatches.all { it.startsWith("env-") })
        assertEquals(listOf(items, items.take(2), items.drop(2)).map { batch ->
            CensusApi.envelopeBatchId(batch.map { it.itemJson })
        }, h.api.envelopeBatches)
        assertTrue(h.envelopes.isEmpty())
        assertNull(h.envelopePending)
        assertEquals(2L, h.stats.envelopesPosted.get())
        assertEquals(2L, h.stats.envelopesRejected.get())
        assertEquals("envelopes_posted 2", h.preferences.censusLastRun.first()?.token())
    }

    @Test fun `envelope unauthorized resigns exact body and transport failure retains batch`() = runTest {
        val h = harness()
        h.initialize()
        val items = h.queueEnvelopes()
        h.api.envelopeResults.addAll(listOf(UploadResult.Unauthorized(null), UploadResult.TransportFailure("IOException")))
        assertEquals(ListenableWorker.Result.retry(), h.worker().doWork())
        assertEquals(listOf(items.map { it.itemJson }, items.map { it.itemJson }), h.api.envelopeBodies)
        assertEquals(h.api.envelopeBatches[0], h.api.envelopeBatches[1])
        assertEquals(items.map { it.id }, h.envelopePending?.ids)
        assertEquals("failure", h.preferences.censusLastRun.first()?.token())
    }

    @Test fun `revoked envelope disables both switches clears envelopes and resets identity`() = runTest {
        val h = harness()
        h.initialize()
        h.queueEnvelopes()
        h.api.envelopeResults += UploadResult.Revoked
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertFalse(h.preferences.censusShareCaptures.first())
        assertFalse(h.preferences.censusUploadEnabled.first())
        assertTrue(h.envelopes.isEmpty())
        verify(h.credentials).wipe()
        assertEquals("revoked", h.preferences.censusLastRun.first()?.token())
    }

    @Test fun `switches rechecked after envelope in flight marker preserve uploaded skeleton outcome`() = runTest {
        for (disableConsent in listOf(false, true)) {
            val h = harness()
            h.initialize()
            h.queue()
            val items = h.queueEnvelopes()
            h.api.uploads += UploadResult.Accepted(1, 0, emptyMap(), CensusBudget(1, 1, 1, 1))
            whenever(h.envelopeSpool.markInFlight(any(), any())).thenAnswer {
                h.envelopePending = CensusSpool.InFlight(it.getArgument(1), it.getArgument<Collection<String>>(0).toList())
                if (disableConsent) h.setConsentSync(false) else h.setShareSync(false)
                Unit
            }
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertTrue(h.api.envelopeBodies.isEmpty())
            assertEquals("uploaded 1", h.preferences.censusLastRun.first()?.token())
            assertEquals(0, h.preferences.censusLastRun.first()?.envelopesPosted)
            assertEquals(items, h.envelopes)
            assertNull(h.envelopePending)
            verify(h.envelopeSpool).clearInFlight()
        }
    }

    @Test fun `switches rechecked before envelope marker clear retry membership and preserve skeleton outcome`() = runTest {
        for (disableConsent in listOf(false, true)) {
            val h = harness()
            h.initialize()
            h.queue()
            val items = h.queueEnvelopes()
            h.api.uploads += UploadResult.Accepted(1, 0, emptyMap(), CensusBudget(1, 1, 1, 1))
            val marker = CensusSpool.InFlight(CensusApi.envelopeBatchId(items.map { it.itemJson }), items.map { it.id })
            h.envelopePending = marker
            whenever(h.envelopeSpool.inFlight()).thenAnswer {
                if (disableConsent) h.setConsentSync(false) else h.setShareSync(false)
                marker
            }
            assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
            assertTrue(h.api.envelopeBodies.isEmpty())
            assertEquals("uploaded 1", h.preferences.censusLastRun.first()?.token())
            assertEquals(items, h.envelopes)
            assertNull(h.envelopePending)
            verify(h.envelopeSpool, never()).markInFlight(any(), any())
            verify(h.envelopeSpool).clearInFlight()
        }
    }

    @Test fun `sharing off during envelope response stops resign and preserves skeleton outcome`() = runTest {
        val h = harness()
        h.initialize()
        h.queue()
        val items = h.queueEnvelopes()
        h.api.uploads += UploadResult.Accepted(1, 0, emptyMap(), CensusBudget(1, 1, 1, 1))
        val checkMembership = h.api.beforeEnvelope
        h.api.beforeEnvelope = { batch, body ->
            checkMembership(batch, body)
            h.setShareSync(false)
        }
        h.api.envelopeResults += UploadResult.Unauthorized(null)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(1, h.api.envelopeBodies.size)
        assertEquals(items, h.envelopes)
        assertNull(h.envelopePending)
        verify(h.envelopeSpool).clearInFlight()
        assertEquals("uploaded 1", h.preferences.censusLastRun.first()?.token())
    }

    @Test fun `three batch cap with 350 skeletons never dispatches an envelope`() = runTest {
        val h = harness()
        h.initialize()
        repeat(3) { h.queueBatch(100) }
        h.queueBatch(50)
        val envelopes = h.queueEnvelopes()
        h.api.uploads.addAll(List(3) { UploadResult.Accepted(100, 0, emptyMap(), CensusBudget(1, 1, 1, 1)) })
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(3, h.api.bodies.size)
        assertEquals(50, h.queued.flatten().size)
        assertEquals(envelopes, h.envelopes)
        assertTrue(h.api.envelopeBodies.isEmpty())
        verify(h.envelopeSpool, never()).take(any(), any())
        assertEquals("uploaded 300", h.preferences.censusLastRun.first()?.token())
    }

    @Test fun `all rejected envelopes are removed counted warned and visible for 422 and accepted responses`() = runTest {
        for (response in listOf(UploadResult.BatchQuality(mapOf("bad_item" to 1)),
            UploadResult.Accepted(0, 0, mapOf("bad_item" to 1), CensusBudget(1, 1, 1, 1)))) {
            val h = harness()
            h.initialize()
            val items = h.queueEnvelopes()
            h.api.envelopeResults += response
            val warnings = mutableListOf<String>()
            val tree = warningTree(warnings)
            Timber.plant(tree)
            try {
                assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
                assertEquals(listOf("census envelopes rejected reasons={bad_item=1}"), warnings)
            } finally {
                Timber.uproot(tree)
            }
            assertEquals("envelopes_rejected 1", h.preferences.censusLastRun.first()?.token())
            assertEquals(0, h.preferences.censusLastRun.first()?.envelopesPosted)
            assertEquals(1L, h.stats.envelopesRejected.get())
            assertEquals(0L, h.stats.envelopesPosted.get())
            assertTrue(h.envelopes.isEmpty())
            assertNull(h.envelopePending)
            verify(h.envelopeSpool).remove(items.map { it.id })
        }
    }

    @Test fun `singleton envelope 413 drops only envelope counter and continues the split`() = runTest {
        val h = harness()
        h.initialize()
        val items = h.queueEnvelopes(2)
        h.api.envelopeResults.addAll(listOf(UploadResult.PayloadTooLarge,
            UploadResult.PayloadTooLarge, UploadResult.Accepted(1, 0, emptyMap(), CensusBudget(1, 1, 1, 1))))
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(listOf(items, items.take(1), items.drop(1)).map { batch -> batch.map { it.itemJson } }, h.api.envelopeBodies)
        assertEquals(1L, h.stats.envelopesDropped.get())
        assertEquals(0L, h.stats.oversized.get())
        assertEquals("envelopes_posted 1", h.preferences.censusLastRun.first()?.token())
        assertTrue(h.envelopes.isEmpty())
        assertNull(h.envelopePending)

        h.queueEnvelopes()
        h.api.envelopeResults += UploadResult.PayloadTooLarge
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals("spool_empty", h.preferences.censusLastRun.first()?.token())
        assertEquals(2L, h.stats.envelopesDropped.get())
        assertEquals(0L, h.stats.oversized.get())
    }

    private fun warningTree(warnings: MutableList<String>): Timber.Tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == Log.WARN && tag == "Census") warnings += message
        }
    }
    @Test fun `mixed batch shares budget deferral and retains both kinds for retry`() = runTest {
        val h = harness()
        h.initialize()
        val items = h.queueMixedBatch()
        h.api.uploads += UploadResult.BudgetExhausted(300)
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(items.map { it.itemJson }, h.api.bodies.single())
        val batchId = h.api.batches.single()
        assertEquals(items.map { it.id }, h.pending?.ids)
        val deadline = h.preferences.nextAllowedAtMillis.first()
        assertTrue(deadline > System.currentTimeMillis())
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(1, h.api.batches.size)
        h.preferences.setNextAllowedAtMillis(System.currentTimeMillis() - 1)
        h.api.uploads += UploadResult.Duplicate
        assertEquals(ListenableWorker.Result.success(), h.worker().doWork())
        assertEquals(listOf(batchId, batchId), h.api.batches)
        assertEquals(listOf(items, items).map { batch -> batch.map { it.itemJson } }, h.api.bodies)
        assertTrue(h.queued.isEmpty())
    }

}
