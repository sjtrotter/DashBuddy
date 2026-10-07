package cloud.trotter.dashbuddy.test.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.view.accessibility.AccessibilityEvent
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import cloud.trotter.dashbuddy.core.data.capability.RuleCapabilityRepository
import cloud.trotter.dashbuddy.core.data.capture.DiskCaptureBus
import cloud.trotter.dashbuddy.core.data.capture.NoOpCaptureBus
import cloud.trotter.dashbuddy.core.data.capture.NoOpCensusSink
import cloud.trotter.dashbuddy.core.data.census.*
import cloud.trotter.dashbuddy.core.data.di.CensusCredentialsModule
import cloud.trotter.dashbuddy.core.data.log.LogRepository
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.dashbuddy.core.network.census.CensusApi
import cloud.trotter.dashbuddy.core.network.census.CensusApiFactory
import cloud.trotter.dashbuddy.core.network.census.CensusTransport
import cloud.trotter.dashbuddy.core.network.di.ClientProfile
import cloud.trotter.dashbuddy.core.network.di.NetworkClientFactory
import cloud.trotter.dashbuddy.core.pipeline.*
import cloud.trotter.dashbuddy.core.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed.WindowsChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toUiNode
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonPublisher
import cloud.trotter.dashbuddy.core.pipeline.notification.NotificationFilter
import cloud.trotter.dashbuddy.core.pipeline.notification.NotificationPipeline
import cloud.trotter.dashbuddy.core.pipeline.notification.input.NotificationSource
import cloud.trotter.dashbuddy.core.pipeline.notification.mapper.toDomain
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.di.AppModule
import cloud.trotter.dashbuddy.domain.capture.*
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.log.StateAwareTree
import cloud.trotter.dashbuddy.worker.CensusUploadWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.*
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext

/**
 * #1271 scenario 6 — real sensor admission → capture → census files → worker → signed bytes,
 * with the real INFO+ file sink beside it. No state/effect/Room graph belongs in this sibling.
 * Storage, Android service/nodes, metadata, Keystore and terminal HTTP are the external edges.
 * This does not exercise Android event-delivery consent, release Hilt wiring or screenshot pixels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyExportReplay(
    private val context: Context,
    private val test: TestScope,
    captures: Boolean = true,
    census: Boolean = true,
    debug: Boolean = true,
) : AutoCloseable {
    private val io = OwnedDispatcher(StandardTestDispatcher(test.testScheduler))
    private val backgroundScope = CoroutineScope(test.backgroundScope.coroutineContext +
        SupervisorJob(test.backgroundScope.coroutineContext[Job]) + io)
    val uploadStats = CensusUploadStats()
    val stats = PipelineStats("privacy-e2e", uploadStats)
    val preferences = DevSettingsRepository(DevSettingsDataSource(ReplayEdges.MemoryPreferences()), debug, io)
    val skeletonSpool = CensusSpool(context, uploadStats, io)
    val envelopeSpool = CensusCredentialsModule.envelopeSpool(context, uploadStats, io)
    val skeletonRoot = File(context.filesDir, "census/spool")
    val envelopeRoot = File(context.filesDir, "census/envelopes")
    val localRoot: File = context.getExternalFilesDir(null) ?: context.filesDir
    val captureRoot = File(localRoot, "captures")
    val log = LogRepository(context, io, AppModule.provideLogScrubber())
    private val tree = StateAwareTree(log, preferences, Provider { "IDLE" })
    val credentials = CensusCredentialStore(ReplayEdges.MemoryPreferences(), object : KeystoreSealer() {
        override fun seal(bytes: ByteArray) = Sealed(byteArrayOf(1), xor(bytes))
        override fun open(iv: ByteArray, ct: ByteArray) = xor(ct)
        override fun reset() = Unit
        private fun xor(bytes: ByteArray) = bytes.map { (it.toInt() xor 85).toByte() }.toByteArray()
    })
    val scheduling = RecordingScheduler()
    private val envelopeSink: CensusEnvelopeSink = if (census)
        PersistentCensusEnvelopeSink(preferences, envelopeSpool, uploadStats, io) else NoOpCensusEnvelopeSink
    private val sink: CensusSink = if (census)
        HttpCensusSink(preferences, skeletonSpool, scheduling, uploadStats, backgroundScope, io) else NoOpCensusSink()
    private val bus: CaptureBus = if (captures) DiskCaptureBus(context, io, envelopeSink, uploadStats) else NoOpCaptureBus()
    private val health = PersistentHealthSink(HealthLedgerStore(ReplayEdges.MemoryPreferences(), uploadStats), uploadStats, backgroundScope, io)
    private val metadata = object : ReplayMetadataProvider {
        override fun current() = ReplayMetadata(engineVersion = 1, appVersion = "privacy-e2e", rulesetReleaseTag = "dev",
            deviceFingerprint = "FAKEDEVICECANARY", rulesetSignature = "FAKESIGNATURECANARY")
    }
    private val interpreter = JsonRuleInterpreter(generatedAssets(context), RuleCapabilityRepository(
        RuleCapabilityDataSource(ReplayEdges.MemoryPreferences()), backgroundScope, "privacy-e2e", Clock.systemUTC()))
    private val classifier = ObservationClassifier(interpreter, metadata, PlatformAppVersions.NONE, Clock.systemUTC())
    private val platforms = object : PlatformPreferences {
        override val enabledPlatforms = MutableStateFlow(setOf(Platform.DoorDash))
        override val enabledPackages = MutableStateFlow(setOf(PrivacyExportInputs.PACKAGE))
        override val graceConfig = MutableStateFlow(emptyMap<Platform, GraceConfig>())
    }
    private val source = AccessibilitySource(stats)
    private val service = mock<AccessibilityService>()
    private val notifications = NotificationSource()
    private val writer = CaptureWriter(bus, stats, interpreter)
    private val publisher = SkeletonPublisher(sink, stats, envelopeSink)
    private val accessibility = AccessibilityPipeline(ContentChangedPipeline(source, platforms, stats),
        StateChangedPipeline(source, platforms, stats), WindowsChangedPipeline(source, platforms, stats),
        source, classifier, writer, platforms, stats, RecognitionHealthMonitor(RecognitionHealthReporter { _, _ -> }, health), publisher)
    private val notificationPipeline = NotificationPipeline(notifications, NotificationFilter(platforms), classifier, writer, platforms, stats, publisher)
    val forwarded = mutableListOf<Observation>()
    val requests = CopyOnWriteArrayList<RequestBytes>()
    private val unexpectedRoutes = CopyOnWriteArrayList<String>()
    var revokeNextSkeleton = false
    var factoryCalls = 0
        private set
    private val client = NetworkClientFactory.okHttpClient("Census", ClientProfile.Census).newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val body = buffer.readByteString()
            val path = request.url.encodedPath
            val root = when (path) { "/v1/skeletons" -> skeletonRoot; "/v1/envelopes" -> envelopeRoot; else -> null }
            val marker = root?.let { File(if (it == skeletonRoot) it.parentFile else it, "inflight.json") }
            requests += RequestBytes(request.method, path, request.headers, body,
                marker?.takeIf { it.isFile }?.readBytes()?.toByteString(), root?.let(::records).orEmpty())
            var status = 200
            val response = when (path) {
                "/v1/enroll", "/v1/policy" -> """{"acceptedSchemaIds":["uinode.skeleton.v1","notification.skeleton.v1"]}"""
                "/v1/skeletons", "/v1/envelopes" -> if (path == "/v1/skeletons" && revokeNextSkeleton) {
                    revokeNextSkeleton = false
                    status = 401
                    """{"error":"revoked"}"""
                } else {
                    val count = Json.parseToJsonElement(body.utf8()).jsonObject.getValue("items").jsonArray.size
                    """{"status":"accepted","accepted":$count,"duplicate":0,"rejected":{},"budget":{"skeletonsRemainingToday":999,"bytesRemainingToday":9000000,"batchesRemainingToday":99,"resetInSeconds":3600}}"""
                }
                else -> { unexpectedRoutes += path; throw IOException("unexpected census route") }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("scripted")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
    private val apiFactory = object : CensusApiFactory() {
        override fun create(baseUrl: String): CensusTransport {
            factoryCalls++
            return CensusApi(client, "https://census.invalid")
        }
    }
    private val lock = CensusUploadLock()

    /** Load unchanged generated assets before starting the only collectors of the production outputs. */
    suspend fun start() {
        assertTrue("fresh capture storage", captures().isEmpty())
        assertTrue("fresh skeleton storage", records(skeletonRoot).isEmpty())
        assertTrue("fresh envelope storage", records(envelopeRoot).isEmpty())
        assertTrue("fresh shareable log", !File(localRoot, "shareable.log").exists())
        Timber.plant(tree)
        whenever(service.packageName).thenReturn(context.packageName)
        whenever(service.resources).thenReturn(context.resources)
        whenever(service.windows).thenReturn(emptyList())
        source.registerService(service)
        interpreter.loadDefaults()
        assertTrue("production rule loader ready", interpreter.isLoaded)
        backgroundScope.launch { accessibility.output().collect { forwarded += it } }
        backgroundScope.launch { notificationPipeline.output().collect { forwarded += it } }
        settle()
    }

    fun settle() = test.testScheduler.runCurrent()

    /** Preflight classification checks the splice; only emit(event) drives persistence and forwarding. */
    @Suppress("DEPRECATION") // obtain/recycle match the framework callback lifetime on SDK 35.
    fun screen(kind: PrivacyExportInputs.Screen, node: UiNode = PrivacyExportInputs.screen(kind)) {
        val before = fileCounts()
        val native = PrivacyExportInputs.mirror(node)
        val mapped = requireNotNull(native.toUiNode())
        assertTrue("native adapter preserves the input tree", PrivacyExportInputs.nodes(node) == PrivacyExportInputs.nodes(mapped))
        val preflight = classifier.classify(PipelineEvent.Screen(System.currentTimeMillis(), mapped,
            TreeSnapshot(mapped, PrivacyExportInputs.PACKAGE), PrivacyExportInputs.PACKAGE))
        assertEquals("mutated input classification: $kind", kind.target, preflight.target)
        whenever(service.rootInActiveWindow).thenReturn(native)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = PrivacyExportInputs.PACKAGE
            className = "android.widget.FrameLayout"
        }
        try { source.emit(event); settle() } finally { event.recycle() }
        if (kind in setOf(PrivacyExportInputs.Screen.BANKING, PrivacyExportInputs.Screen.BANKING_UNKNOWN)) {
            assertEquals("sensitive screen creates no capture or census record", before, fileCounts())
        }
    }

    fun notification(kind: PrivacyExportInputs.Push) {
        val before = fileCounts()
        val notification = PrivacyExportInputs.notification(context, kind, System.currentTimeMillis())
        val raw = requireNotNull(notification.toDomain())
        assertEquals("notification splice stays UNKNOWN: $kind", "UNKNOWN",
            classifier.classify(PipelineEvent.Notification(raw.postTime, raw)).target)
        notifications.emit(notification)
        settle()
        if (kind == PrivacyExportInputs.Push.BANKING) assertEquals("sensitive action creates no capture or census record", before, fileCounts())
    }

    /** Immediate repetitions must be suppressed before capture and census publication. */
    fun manifest(repeatBenign: Boolean = false) {
        for (kind in PrivacyExportInputs.Screen.entries) {
            screen(kind)
            if (repeatBenign && kind == PrivacyExportInputs.Screen.UNKNOWN) duplicate { screen(kind) }
        }
        for (kind in PrivacyExportInputs.Push.entries) {
            notification(kind)
            if (repeatBenign && kind == PrivacyExportInputs.Push.BENIGN) duplicate { notification(kind) }
        }
    }
    private fun duplicate(emit: () -> Unit) {
        val before = fileCounts()
        val suppressed = stats.suppressedDuplicateCount
        emit()
        assertEquals("repeat adds no files", before, fileCounts())
        assertEquals("admission counted duplicate", suppressed + 1, stats.suppressedDuplicateCount)
    }
    private fun fileCounts() = listOf(captures().size, records(skeletonRoot).size, records(envelopeRoot).size)

    /** Genuine WorkerParameters, production worker, real callback completion; never advanceUntilIdle. */
    suspend fun worker() {
        val worker = TestListenableWorkerBuilder<CensusUploadWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    CensusUploadWorker(appContext, workerParameters, preferences, credentials, skeletonSpool, apiFactory,
                        scheduling, uploadStats, lock, health, metadata, envelopeSpool, envelopeSink)
            }).build()
        assertEquals("worker completes", ListenableWorker.Result.success(), worker.doWork())
        settle()
        assertTrue("unexpected HTTP route: $unexpectedRoutes", unexpectedRoutes.isEmpty())
    }

    fun captures(): List<File> = captureRoot.walkTopDown().filter { it.isFile }.toList()
    fun shareable(): ByteArray = File(localRoot, "shareable.log").takeIf { it.isFile }?.readBytes() ?: byteArrayOf()

    /** Immutable request and in-flight file snapshots survive the worker's acknowledgement/removal. */
    data class RequestBytes(val method: String, val path: String, val headers: Headers, val body: ByteString,
        val marker: ByteString?, val queued: List<Stored>)
    data class Stored(val name: String, val bytes: ByteString) {
        override fun toString(): String = "Stored($name, [bytes omitted])"
        val wrapper: JsonObject get() = Json.parseToJsonElement(bytes.utf8()).jsonObject
        // Mirror the spool's byte-preserving wrapper delimiters, not a JSON reserialization.
        val itemJson: String get() = bytes.utf8().substringAfter("\"skeleton\":").substringBeforeLast(",\"captureId\":")
    }
    fun records(root: File): List<Stored> = root.listFiles().orEmpty()
        .filter { it.isFile && it.name.matches(Regex("[0-9]{1,19}-[0-9]{1,19}\\.json")) }
        .sortedBy { it.name }.map { Stored(it.name, it.readBytes().toByteString()) }

    class RecordingScheduler : CensusUploadScheduler {
        val requests = mutableListOf<String>()
        override fun enqueueNow(replaceQueued: Boolean) { requests += "now:$replaceQueued" }
        override fun enqueueSoon() { requests += "soon" }
        override fun deferUntil(epochMillis: Long) { requests += "defer:$epochMillis" }
    }

    /** Drain writes before cancellation, including private production IO consumers, then release HTTP. */
    override fun close() {
        settle()
        Timber.uproot(tree)
        backgroundScope.cancel()
        io.cancelOwnedJobs()
        settle()
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** Track IO jobs without replacing production scopes or adding a production teardown seam. */
    @OptIn(InternalCoroutinesApi::class)
    private class OwnedDispatcher(private val delegate: TestDispatcher) : CoroutineDispatcher(), Delay by delegate {
        private val jobs = ConcurrentHashMap.newKeySet<Job>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            context[Job]?.let { jobs += it }
            delegate.dispatch(context, block)
        }
        fun cancelOwnedJobs() = jobs.forEach { it.cancel() }
    }

    private fun generatedAssets(app: Context): Context {
        val dir = File(TestRulesetFactory.rulesDir)
        val assets = mock<AssetManager>()
        whenever(assets.list("rules")).thenReturn(dir.list())
        whenever(assets.open(any<String>())).thenAnswer {
            File(dir, it.getArgument<String>(0).removePrefix("rules/")).inputStream()
        }
        return object : ContextWrapper(app) { override fun getAssets(): AssetManager = assets }
    }
}
