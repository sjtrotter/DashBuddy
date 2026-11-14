package cloud.trotter.log.gig.worker

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
import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusCredentialStore.Credential
import cloud.trotter.log.gig.core.data.census.CensusSpool
import cloud.trotter.log.gig.BuildConfig
import cloud.trotter.log.gig.core.data.census.HealthLedgerStore
import cloud.trotter.log.gig.core.data.census.PersistentHealthSink
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.capture.ReplayMetadataProvider
import cloud.trotter.log.gig.domain.census.HealthKey
import cloud.trotter.log.gig.domain.census.HealthLedger
import cloud.trotter.log.gig.domain.census.HealthRow
import cloud.trotter.log.gig.core.network.census.HealthResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.CensusApi
import cloud.trotter.log.gig.core.network.census.CensusBudget
import cloud.trotter.log.gig.core.network.census.CensusTransport
import cloud.trotter.log.gig.core.network.census.CensusSentObserver
import cloud.trotter.log.gig.core.network.census.DeleteResult
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.network.census.EnrolResult
import cloud.trotter.log.gig.core.network.census.PolicyResult
import cloud.trotter.log.gig.core.network.census.UploadResult
import cloud.trotter.log.gig.domain.census.EnvelopeProjection
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.census.CensusConsentReceipt
import cloud.trotter.log.gig.domain.census.CensusEnrolRejectReason
import cloud.trotter.log.gig.domain.census.CensusLastRun
import cloud.trotter.log.gig.domain.census.CensusRunOutcome
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.core.data.census.CensusNetwork
import cloud.trotter.log.gig.core.data.census.CensusUploadPlanner
import cloud.trotter.log.gig.domain.census.CensusUploadStats
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

internal class CensusMemoryPreferences : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    /** A write whose result matches fails once, as a full disk or a process death mid-write would leave it. */
    var failOnce: ((Preferences) -> Boolean)? = null
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(data.value)
        failOnce?.let { if (it(next)) { failOnce = null; throw java.io.IOException("write failed") } }
        data.value = next
        return next
    }
}

internal class FakeCensusApi : CensusTransport {
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
    val declaredSchemaIds = mutableListOf<List<String>>()
    val batches = mutableListOf<String>()
    val bodies = mutableListOf<List<String>>()
    var beforeUpload: (String, List<String>) -> Unit = { _, _ -> }
    override suspend fun enrol(cred: Bearer.Credential, appVersion: String, schemaIds: List<String>): EnrolResult {
        enrolledIds += cred.installId
        declaredSchemaIds += schemaIds
        return enrollments.removeFirst()
    }
    var policyResult: PolicyResult = PolicyResult.Unavailable(503)
    var policyCalls = 0
    override suspend fun policy(): PolicyResult = policyResult.also { policyCalls++ }
    override suspend fun uploadSkeletons(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult {
        stages += "skeletons"
        beforeUpload(batchId, itemsJson)
        batches += batchId
        bodies += itemsJson
        return uploads.removeFirst()
    }
    val deletions = ArrayDeque<DeleteResult>()
    val deletedIds = mutableListOf<String>()
    var beforeDelete: () -> Unit = {}
    override suspend fun deleteData(cred: Bearer.Credential): DeleteResult {
        beforeDelete()
        stages += "delete"
        deletedIds += cred.installId
        return deletions.removeFirst()
    }
}

internal class RecordingCensusScheduler : CensusUploadScheduler {
    val daily = mutableListOf<Pair<Long, Boolean>>()
    override fun enqueueNow(wifiOnly: Boolean, replaceQueued: Boolean) = Unit
    override fun scheduleDaily(runAtMillis: Long, wifiOnly: Boolean) { daily += runAtMillis to wifiOnly }
    var cancelled = 0
    override fun cancelManual() { cancelled++ }
    var deletions = 0
    override fun enqueueDeletion() { deletions++ }
}

/**
 * The census worker over a fake transport, mocked spools and the real settings store, shared by
 * [CensusUploadWorkerTest] and [CensusDeletionWorkerTest].
 */
internal class CensusWorkerHarness(scope: CoroutineScope, io: CoroutineDispatcher) {
    val memory = CensusMemoryPreferences()
    val preferences = spy(DevSettingsRepository(DevSettingsDataSource(memory), true, Dispatchers.Unconfined, "test"))
    val credentials: CensusCredentialStore = mock()
    val spool: CensusSpool = mock()
    val envelopeSpool: CensusSpool = mock()
    val envelopeSink: CensusEnvelopeSink = mock()
    val envelopes = mutableListOf<CensusSpool.Spooled>()
    var envelopePending: CensusSpool.InFlight? = null
    val api = FakeCensusApi()
    val scheduler = RecordingCensusScheduler()
    val planner = CensusUploadPlanner(preferences, scheduler, java.time.Clock.systemUTC())
    var metered = false
    val network = object : CensusNetwork(RuntimeEnvironment.getApplication()) {
        override fun isMetered() = metered
    }
    val stats = CensusUploadStats()
    val healthMemory = CensusMemoryPreferences()
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
    private val factory = object : CensusApiFactory(CensusSentObserver.NONE) {
        override fun create(baseUrl: String): CensusTransport {
            factoryCalls++
            return api
        }
    }
    private val lock = CensusUploadLock()
    fun resetter() = cloud.trotter.log.gig.core.data.census.CensusIdentityResetter(
        credentials, spool, preferences, scheduler, lock, resetScope, healthSink, envelopeSink, deletion,
    )
    private val resetScope = scope
    val deletion = CensusDataDeletion(credentials, spool, envelopeSink, healthSink, preferences, scheduler, lock, factory, scope, "test")

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
        whenever(spool.clear()).thenAnswer { queued.clear(); pending = null; Unit }
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

    /** #1621: replace the stored receipt with one accepted for [endpoint], as a build with another server would find it. */
    fun setReceiptEndpointSync(endpoint: String) {
        val receipt = CensusConsentReceipt(ConsentReceipt(1L, "test", PrivacyDisclosure.REVISION, granted = true), endpoint)
        memory.data.value = memory.data.value.toMutablePreferences().apply {
            this[androidx.datastore.preferences.core.stringPreferencesKey("census_consent_receipt_json")] =
                Json.encodeToString(CensusConsentReceipt.serializer(), receipt)
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

    fun queueBatch(count: Int, day: LocalDate = LocalDate.now(ZoneOffset.UTC), platform: String = "doordash"): List<CensusSpool.Spooled> {
        val items = List(count) {
            val index = ++sequence
            CensusSpool.Spooled("1-$index.json", index.toString().padStart(64, '0'), "{\"item\":$index,\"platform\":\"$platform\",\"day\":\"$day\"}")
        }
        queued += items
        return items
    }

    fun queueMixedBatch(): List<CensusSpool.Spooled> {
        val day = LocalDate.now(ZoneOffset.UTC)
        val meta = ReplayMetadata(engineVersion = 1)
        val screen = cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder.outcome(
            cloud.trotter.log.gig.domain.model.accessibility.UiNode(text = "Continue"), null,
            meta, cloud.trotter.log.gig.domain.state.Platform.DoorDash, day,
        ) as cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder.Outcome.Built
        val notification = cloud.trotter.log.gig.core.pipeline.census.NotificationSkeletonBuilder.outcome(
            cloud.trotter.log.gig.domain.model.notification.RawNotificationData(
                "Continue", null, null, null, packageName = cloud.trotter.log.gig.domain.state.Platform.DoorDash.packageName!!,
                postTime = 0, isClearable = true, channelId = "synthetic",
            ), meta, cloud.trotter.log.gig.domain.state.Platform.DoorDash, day,
        ) as cloud.trotter.log.gig.core.pipeline.census.NotificationSkeletonBuilder.Outcome.Built
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

    fun worker(tags: List<String> = emptyList()): CensusUploadWorker = TestListenableWorkerBuilder<CensusUploadWorker>(RuntimeEnvironment.getApplication())
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                CensusUploadWorker(appContext, workerParameters, preferences, credentials, spool, factory, planner, network, stats, lock, healthSink, metadataProvider, envelopeSpool, envelopeSink, deletion)
        }).setTags(tags).build()
}

internal fun TestScope.harness() = CensusWorkerHarness(backgroundScope, StandardTestDispatcher(testScheduler))
