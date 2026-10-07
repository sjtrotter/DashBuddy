package cloud.trotter.dashbuddy.test.util

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.os.Looper
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsProjector
import cloud.trotter.dashbuddy.core.data.analytics.TimeConstantRepository
import cloud.trotter.dashbuddy.core.data.capability.RuleCapabilityRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.dashbuddy.core.datastore.odometer.OdometerLocalDataSource
import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.core.datastore.strategy.StrategyDataSource
import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PipelineV2
import cloud.trotter.dashbuddy.core.pipeline.PlatformAppVersions
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.core.state.CrossPlatformRegionStepper
import cloud.trotter.dashbuddy.core.state.EffectExecutor
import cloud.trotter.dashbuddy.core.state.EffectMap
import cloud.trotter.dashbuddy.core.state.FlowRegionStepper
import cloud.trotter.dashbuddy.core.state.MetadataProvider
import cloud.trotter.dashbuddy.core.state.ObservationJournal
import cloud.trotter.dashbuddy.core.state.PlatformRegionStepper
import cloud.trotter.dashbuddy.core.state.SnapshotStore
import cloud.trotter.dashbuddy.core.state.StateMachine
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.core.state.TransitionPolicy
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.event.EventMetadata
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.notice.TtsHealthNotifier
import cloud.trotter.dashbuddy.state.effects.OdometerEffectHandler
import cloud.trotter.dashbuddy.state.effects.OfferActionReceiver
import cloud.trotter.dashbuddy.state.effects.PermissionTierChecker
import cloud.trotter.dashbuddy.state.effects.ScreenShotHandler
import cloud.trotter.dashbuddy.state.effects.SideEffectEngine
import cloud.trotter.dashbuddy.state.effects.TipEffectHandler
import cloud.trotter.dashbuddy.state.effects.TtsEffectHandler
import cloud.trotter.dashbuddy.state.effects.UiInteractionHandler
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Clock

/**
 * #1271 — the END-TO-END session-replay harness: real captures → the production classifier →
 * the production `StateManagerV2`/`StateMachine` → the production `SideEffectEngine` and its
 * handlers → a real (in-memory) Room → the production `AnalyticsProjector`.
 *
 * [SessionReplay] stops at `StateMachine.step` and reads the emitted `LogEvent`s; this sibling
 * EXECUTES them. Nothing on the inside is faked or fabricated — no evaluation, no event row, no
 * timeout: an offer is scored by the real `OfferEvaluator` in the engine's loopback, every
 * `app_events` row is written by the engine's own `LogEvent` execution, and every grace / settle /
 * expiry fires from the engine's own timer coroutine. The fakes are the external edges only
 * ([ReplayEdges]): the clock, preference storage, GPS, the TTS engine, the third-party
 * accessibility tree and the notification UI.
 *
 * **Time.** One [TestCoroutineScheduler] drives every coroutine (engine, manager, journal, Room's
 * query context, repositories) and the [ReplayEdges.SchedulerClock] reads it, so
 * `clock = fixtureEpoch + scheduler.currentTime`. [advanceTo] moves virtual time to an absolute
 * fixture instant, firing exactly the timers due before it; nothing ever calls an unrestricted
 * `advanceUntilIdle`, which would fire every FUTURE deadline (a grace that the next frame should
 * have superseded). A frame is classified AT its instant, so the observation timestamp and the
 * parse-time transforms (`parseDeadline`) agree with the capture, with no re-stamping.
 *
 * **Sensor gating.** The harness forwards a classified frame the way `AccessibilityPipeline.output()`
 * does after its gates: UNKNOWN and sensitive frames are recorded but never reach the state machine.
 * Frame-identity dedup is not modelled — every replayed frame is a capture the device admitted.
 *
 * Run under Robolectric with `@Config(application = ReplayApplication::class)`; build one harness
 * per test and [close] it (cancels every scope, closes the DB).
 *
 * **Restart (#1271 scenario 4).** Hand a harness [ReplayEdges.DurableStores] and its database is a
 * FILE and its preference stores outlive it; [crash] then kills it the way the OS kills the
 * process — nothing in flight finishes — and a second harness built over the same stores is the
 * relaunched app: it restores from the snapshot + journal that actually reached disk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class E2ESessionReplay(
    private val app: ReplayApplication,
    fixtureEpochMs: Long,
    /**
     * What survives a process death: the database file and the preference stores. Null (the default)
     * is a fresh in-memory database and fresh stores — one launch, nothing to restart into.
     */
    durable: ReplayEdges.DurableStores? = null,
    /**
     * The persisted consent store. Defaults to [durable]'s, else a fresh one; a test that models an
     * app restart (a ruleset update between two launches) hands the SAME store to the second harness.
     */
    grantStore: DataStore<Preferences> = durable?.grants ?: ReplayEdges.MemoryPreferences(),
    /**
     * Applied to each generated rule file's JSON (file name, contents) before the production loader
     * reads it — the stand-in for a ruleset UPDATE (#1271 scenario 5's repointed binding). Identity by
     * default: the loader reads the generated bytes unchanged.
     */
    ruleTransform: (file: String, json: String) -> String = { _, json -> json },
) : AutoCloseable {

    val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    val clock: Clock = ReplayEdges.SchedulerClock(scheduler, fixtureEpochMs)
    private val appScope = CoroutineScope(dispatcher + SupervisorJob())

    /** Owns every [await] block, so a block that failed its check is still cancelled on [close]. */
    private val workScope = CoroutineScope(dispatcher + SupervisorJob())

    /**
     * The fixtures' real zone. `ObservationClassifier` hands `TransformRegistry` the device-default
     * zone (production behaviour, deliberately not a seam), so a wall-clock render like "Pick up by
     * 15:42" parses to the right instant only in the zone it was captured in. Pinned for the
     * harness's lifetime and restored on [close].
     */
    private val hostZone: java.util.TimeZone = java.util.TimeZone.getDefault().also {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(FIXTURE_ZONE))
    }

    // ── Persistence (real Room, real DAOs) ─────────────────────────────────────────────────────
    val db: DashBuddyDatabase = (
        if (durable == null) Room.inMemoryDatabaseBuilder(app, DashBuddyDatabase::class.java)
        else Room.databaseBuilder(app, DashBuddyDatabase::class.java, durable.dbFile.absolutePath)
        )
        .allowMainThreadQueries()
        .setQueryCoroutineContext(dispatcher)
        .build()
    val appEventRepo = AppEventRepo(db, db.appEventDao(), db.effectsFiredDao(), clock)
    val projector: AnalyticsProjector

    // ── Preferences (real data sources + repositories over memory stores) ──────────────────────
    val appPreferences = AppPreferencesRepository(AppPreferencesDataSource(durable?.app ?: ReplayEdges.MemoryPreferences()))
    val strategy = StrategyRepository(
        StrategyDataSource(durable?.strategy ?: ReplayEdges.MemoryPreferences()), appPreferences, dispatcher,
        TimeConstantRepository(db.timeConstantDao()),
    )
    val grants = RuleCapabilityRepository(
        RuleCapabilityDataSource(grantStore), appScope, "e2e-replay", clock,
    )

    // ── External edges ─────────────────────────────────────────────────────────────────────────
    val location = ReplayEdges.FakeLocation(clock)
    val odometer = OdometerRepository(
        OdometerLocalDataSource(durable?.odometer ?: ReplayEdges.MemoryPreferences()), location, dispatcher, clock,
    )
    val notifications: NotificationManager = app.getSystemService(NotificationManager::class.java)
    val tts = ReplayEdges.FakeTts()
    val accessibility = ReplayEdges.FakeAccessibility()
    val bubble = ReplayEdges.RecordingBubble(app, notifications)
    val screenshots: ScreenShotHandler = mock()

    // ── Recognition (the production loader over the generated rule assets) ────────────────────
    private val interpreter = JsonRuleInterpreter(generatedRuleAssets(app, ruleTransform), grants)
    private val classifier = ObservationClassifier(
        interpreter,
        object : ReplayMetadataProvider { override fun current() = ReplayMetadata.EMPTY },
        PlatformAppVersions.NONE,
        clock,
    )

    // ── Effects + state ────────────────────────────────────────────────────────────────────────
    val engine: SideEffectEngine
    val executor: RecordingExecutor
    private val sensor = MutableSharedFlow<StateEvent>(extraBufferCapacity = 1024)
    val manager: StateManagerV2

    /** Every input the harness fed, in order, with what it classified as and whether it was forwarded. */
    data class Input(val atMs: Long, val label: String, val observation: Observation?, val forwarded: Boolean)

    val inputs = mutableListOf<Input>()

    /** `manager.state` after each input settled — the per-step state history. */
    val states = mutableListOf<AppState>()

    init {
        projector = AnalyticsProjector(db, appEventRepo, db.appEventDao(), db.analyticsDao(), appPreferences)
        val metadata = MetadataProvider {
            // The production stamp (DashBuddyApplication.createMetadata) minus the device-only fields:
            // the REAL odometer repository's cumulative miles at execution time.
            Gson().toJson(EventMetadata(odometer = odometer.getCurrentMiles(), appVersion = "e2e-replay"))
        }
        engine = SideEffectEngine(
            appEventRepo = appEventRepo,
            odometerEffectHandler = OdometerEffectHandler(app, odometer, notifications),
            tipEffectHandler = TipEffectHandler(bubble.manager, app, dispatcher),
            bubbleManager = bubble.manager,
            offerEvaluator = OfferEvaluator(),
            strategyRepository = strategy,
            screenShotHandler = screenshots,
            uiInteractionHandler = UiInteractionHandler(accessibility.source),
            effectsFiredDao = db.effectsFiredDao(),
            ttsEffectHandler = TtsEffectHandler(
                app, appPreferences, appScope, tts.factory, TtsHealthNotifier(app, notifications), clock,
            ),
            permissionTierChecker = PermissionTierChecker(app, accessibility.source),
            capabilityGrants = grants,
            metadataProvider = metadata,
            defaultDispatcher = dispatcher,
            clock = clock,
        )
        executor = RecordingExecutor(engine, clock)
        val pipeline = mock<PipelineV2> { on { events } doReturn sensor }
        manager = StateManagerV2(
            pipeline = pipeline,
            engine = executor,
            stateMachine = StateMachine(
                flowStepper = FlowRegionStepper(),
                platformStepper = PlatformRegionStepper(),
                crossPlatformStepper = CrossPlatformRegionStepper(),
                transitionPolicy = TransitionPolicy(),
                effectMap = EffectMap(),
            ),
            journal = ObservationJournal(db.observationDao()),
            snapshots = SnapshotStore(db.appStateSnapshotDao(), clock),
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            clock = clock,
        )
        app.component = ReplayApplication.Component(manager, clock)
    }

    /**
     * Migrate the consent store, load the rules, bring the speech engine up and start the manager — the app-start sequence,
     * minus the parts that live outside this boundary. Returns once the manager is collecting.
     */
    fun start() {
        // Production order (DashBuddyApplication.onCreate): the one-shot consent-schema migration runs
        // first, so rules never load over an un-migrated (pre-#1167) grant store…
        await { grants.migrateConsentSchemaIfNeeded() }
        // …then the #1113 optional dead-automation-key purge, isolated exactly as production isolates
        // it (a failure never keeps the rules unloaded) …
        await { runCatching { strategy.purgeDeadAutomationKeys() } }
        // …then the rules go live.
        await { interpreter.loadDefaults() }
        check(interpreter.isLoaded) { "the production rule loader loaded nothing" }
        tts.reportReady()
        manager.initialize()
        settle()
        check(sensor.subscriptionCount.value > 0) { "the state manager never subscribed to the sensor stream" }
    }

    /** Now, on the harness clock. */
    val nowMs: Long get() = clock.millis()

    /** Advance virtual time to the absolute instant [atMs], running exactly what is due on the way. */
    fun advanceTo(atMs: Long) {
        require(atMs >= nowMs) { "time runs forward: $atMs < $nowMs" }
        scheduler.advanceTimeBy(atMs - nowMs)
        settle()
    }

    /** Run everything ready NOW (scheduler + main looper), deliver queued utterance completions. */
    fun settle() {
        repeat(3) {
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            tts.deliverDone()
        }
        scheduler.runCurrent()
    }

    /** Settle, fold the log into the read model through the projector, settle again. */
    fun drain() {
        settle()
        await { projector.catchUp() }
        settle()
    }

    /** Run a suspend [block] on the harness dispatcher to completion (no virtual time passes). */
    fun <T> await(block: suspend () -> T): T {
        val deferred = workScope.async { block() }
        repeat(50) {
            if (deferred.isCompleted) return deferred.getCompleted()
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
        check(deferred.isCompleted) { "await: the block is waiting on virtual time or a foreign thread" }
        return deferred.getCompleted()
    }

    /** Feed a captured SCREEN [frame] at [atMs] (its capture instant by default) through the classifier. */
    fun screen(frame: SessionReplay.ReplayFrame, atMs: Long = frame.capturedAtMs, node: UiNode = frame.node): Observation.Screen {
        advanceTo(atMs)
        val pkg = packageOf(frame.wire)
        val obs = classifier.classify(
            PipelineEvent.Screen(timestamp = atMs, tree = node, snapshot = TreeSnapshot(node, packageName = pkg), packageName = pkg),
        )
        accessibility.show(node, pkg)
        forward(atMs, "screen ${frame.file}", obs)
        return obs
    }

    /** Feed a captured CLICK envelope at [atMs] through the classifier. */
    fun click(click: SessionReplay.ClickInput, atMs: Long = click.atMs): Observation.Click {
        advanceTo(atMs)
        val obs = classifier.classify(
            PipelineEvent.Click(timestamp = atMs, node = click.node, packageName = packageOf(click.wire)),
        )
        forward(atMs, "click ${click.screenTarget}", obs)
        return obs
    }

    /**
     * Tap the posted offer heads-up's [action] button at [atMs]: the action's own PendingIntent is
     * delivered to the production [OfferActionReceiver.onReceive], as SystemUI would.
     */
    fun tapOfferNotification(action: String, atMs: Long) {
        advanceTo(atMs)
        val posted = shadowOf(notifications).allNotifications
        val button = posted.flatMap { it.actions?.toList().orEmpty() }.singleOrNull { it.title == action }
            ?: error("no posted offer notification carries a '$action' action (posted=${posted.size})")
        deliverOfferAction(shadowOf(button.actionIntent).savedIntent, atMs, "tap $action")
    }

    /**
     * The broadcast Intent behind the [action] button of the heads-up CURRENTLY posted for
     * [offerHash] — kept by a test so it can be delivered LATER, after that banner is gone: a stale
     * tap racing the banner's replacement (the dasher's finger lands as SystemUI swaps it).
     */
    fun offerActionIntent(action: String, offerHash: String): android.content.Intent {
        val n = notifications.activeNotifications.singleOrNull { it.id == BubbleManager.offerNotificationId(offerHash) }
            ?: error("no heads-up is posted for offer $offerHash")
        val button = n.notification.actions.orEmpty().singleOrNull { it.title == action }
            ?: error("the heads-up for $offerHash has no '$action' action")
        return shadowOf(button.actionIntent).savedIntent
    }

    /** Deliver an offer-action broadcast [intent] at [atMs] to the production [OfferActionReceiver.onReceive]. */
    fun deliverOfferAction(intent: android.content.Intent, atMs: Long, label: String = "offer action") {
        advanceTo(atMs)
        OfferActionReceiver().onReceive(app, intent)
        inputs += Input(atMs, label, null, forwarded = true)
        settle()
        states += manager.state.value
    }

    /** The ids of the notifications currently posted (the shade). */
    fun activeNotificationIds(): Set<Int> = notifications.activeNotifications.map { it.id }.toSet()

    /**
     * Feed one captured session directory: its SCREEN frames plus the named CLICK envelopes, merged
     * in capture order, then hold [tailMs] past the last so every grace the run armed is served by
     * the engine's own timer.
     */
    fun feedSession(path: String, clickFiles: List<String>, tailMs: Long) {
        val screens = SessionReplay.loadSession(path).map { it.capturedAtMs to { screen(it); Unit } }
        val clicks = clickFiles.map { SessionReplay.loadClickFrame("$path/$it") }.map { it.atMs to { click(it); Unit } }
        val ordered = (screens + clicks).sortedBy { it.first }
        ordered.forEach { it.second() }
        advanceTo(ordered.last().first + tailMs)
    }

    private fun forward(atMs: Long, label: String, obs: Observation.FlowObservation) {
        val forwarded = obs.target != UNKNOWN_TARGET && obs.parsed !is ParsedFields.SensitiveFields
        if (forwarded) check(sensor.tryEmit(obs)) { "sensor buffer full" }
        inputs += Input(atMs, label, obs, forwarded)
        settle()
        states += manager.state.value
    }

    private fun packageOf(wire: String): String? = Platform.entries.firstOrNull { it.wire == wire }?.packageName

    /** One readable line per input: time, source, recognized target, forwarded or gated. */
    fun trace(): String = buildString {
        inputs.forEach { i ->
            val target = (i.observation as? Observation.FlowObservation)?.target ?: "-"
            appendLine("%d %-60s %-28s %s".format(i.atMs, i.label.take(60), target, if (i.forwarded) "" else "(gated)"))
        }
        executor.trace.forEach { appendLine("  fx %d %s".format(it.atMs, it.effect::class.simpleName)) }
    }

    /**
     * Kill this "process" (#1271 scenario 4): exactly [close] — every scope is cancelled where it
     * stands, so an effect still queued in the engine, a journal row still queued for its writer or
     * a timer still waiting never runs, while what already committed stays on disk. Nothing flushes
     * on the way down (the odometer repository persists per fix, never on stop). Named separately so
     * a test says which it means.
     */
    fun crash() = close()

    /** Rows in [table] (a read on the real database). */
    fun rowCount(table: String): Long =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    /** Every read-model table, complete, as ordered rows of column → value. */
    fun readModel(): Map<String, List<Map<String, String?>>> =
        READ_MODEL_TABLES.associateWith { table -> rows("SELECT * FROM $table ORDER BY 1") }

    /** The rows [sql] returns, each as column → value (a read on the real database). */
    fun rows(sql: String): List<Map<String, String?>> =
        db.openHelper.readableDatabase.query(sql).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add((0 until c.columnCount).associate { c.getColumnName(it) to (if (c.isNull(it)) null else c.getString(it)) })
                }
            }
        }

    /**
     * Cancel every scope, then run the scheduler at the current instant so the cancellations
     * actually COMPLETE (a `StandardTestDispatcher` resumes cancelled continuations only when run:
     * GPS `onCompletion`, collector loops, DAO calls in flight). Only then is Room closed. Fails if a
     * GPS collector survived. The odometer repository's private scope has no long-lived job besides
     * the tracking job `stopTracking()` cancels; its fire-and-forget saves finish in the same pass.
     */
    override fun close() {
        try {
            engine.close()
            manager.close()
            strategy.close()
            odometer.stopTracking()
            appScope.cancel()
            workScope.cancel()
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            scheduler.runCurrent()
            check(location.activeCollectors == 0) { "a GPS collector survived teardown (${location.activeCollectors})" }
        } finally {
            app.component = null
            db.close()
            java.util.TimeZone.setDefault(hostZone)
        }
    }

    /**
     * The production engine, wrapped: every effect the manager hands it is recorded (with the clock
     * at hand-off) and forwarded unchanged — the effect trace. Execution evidence comes from the
     * edges (TTS, clicks, notifications, GPS) and from Room, never from this list.
     */
    class RecordingExecutor(private val inner: EffectExecutor, private val clock: Clock) : EffectExecutor {
        data class Entry(
            val atMs: Long,
            val effect: AppEffect,
            val recovering: Boolean,
            val correlationVersion: Long,
            /** Handed over while [severed]: recorded, never executed. */
            val dropped: Boolean = false,
        )

        val trace = mutableListOf<Entry>()
        override val events: SharedFlow<StateEvent> get() = inner.events

        /**
         * While true, effects (and [afterProcessed] barriers) are recorded but NOT forwarded — the process died after the manager
         * stepped and journalled an observation and before the engine's queue ran its effects
         * (#1271 scenario 4). Only meaningful right before a [crash].
         */
        var severed = false

        override fun process(effect: AppEffect, recovering: Boolean, correlationVersion: Long) {
            trace += Entry(clock.millis(), effect, recovering, correlationVersion, dropped = severed)
            if (!severed) inner.process(effect, recovering, correlationVersion)
        }

        /** Forwarded — unless [severed]: a barrier queued behind lost effects is lost with them. */
        override fun afterProcessed(action: suspend () -> Unit) {
            if (!severed) inner.afterProcessed(action)
        }
    }

    companion object {
        /** Where every committed DoorDash/Uber fixture was captured (San Antonio, TX). */
        const val FIXTURE_ZONE = "America/Chicago"

        /** The read-model tables [readModel] returns (the projection of `app_events`). */
        val READ_MODEL_TABLES = listOf("delivery_records", "session_records", "offer_records", "pickup_records", "stores")

        /** Handler tags — an INFO+ line under one of these was written by the effect layer itself. */
        val HANDLER_TAGS = setOf("Effects", "Tts", "Odometer", "ShopRate", "Chat")

        /**
         * The production `JsonRuleInterpreter` reads `assets/rules/`; unit tests do not merge assets,
         * so this context serves the GENERATED canonical rule files ([TestRulesetFactory.rulesDir]) at
         * that path — the loader, compiler and capability enumeration run unchanged.
         */
        private fun generatedRuleAssets(app: Context, transform: (String, String) -> String): Context {
            val dir = File(TestRulesetFactory.rulesDir)
            val assets = mock<AssetManager>()
            whenever(assets.list("rules")).thenReturn(dir.list())
            whenever(assets.open(any<String>())).thenAnswer {
                val file = File(dir, it.getArgument<String>(0).removePrefix("rules/"))
                transform(file.name, file.readText()).byteInputStream()
            }
            return object : ContextWrapper(app) {
                override fun getAssets(): AssetManager = assets
            }
        }
    }
}

/**
 * #590 / #551 / #1271 — the INFO+ PII-safe-by-construction gate, evaluated over what the REAL
 * effect layer logged during an [E2ESessionReplay] run (Development Principle 7: INFO+ is the
 * shareable bug-report stream, so a raw merchant/customer/address string there is a privacy defect
 * of the same class as leaking it to disk).
 *
 * It is NOT vacuous: it requires INFO+ records at all, and at least one written by a handler
 * ([E2ESessionReplay.HANDLER_TAGS]) after the harness finished starting, before it scans them.
 */
object InfoPlusPiiGate {

    /** Raw store/address strings the run's own parse produced — the deny-list, from the data. */
    fun denyList(replay: E2ESessionReplay): Set<String> {
        val out = mutableSetOf<String>()
        replay.inputs.mapNotNull { (it.observation as? Observation.FlowObservation)?.parsed }.forEach { p ->
            when (p) {
                is ParsedFields.TaskFields -> {
                    p.storeName?.let { out += it }
                    p.storeAddress?.let { out += it }
                }
                is ParsedFields.NotificationFields -> p.storeName?.let { out += it }
                is ParsedFields.OfferFields -> p.parsedOffer.orders.forEach { out += it.storeName }
                else -> {}
            }
        }
        // A 1-2 char token is not a realistic leak anchor and would false-match unrelated text.
        return out.map { it.trim() }.filter { it.length >= 3 }.toSet()
    }

    /**
     * [records] is everything the planted [RecordingTree] saw; [bootstrapRecords] how many of them were
     * logged before the harness finished [E2ESessionReplay.start].
     */
    fun assertClean(label: String, replay: E2ESessionReplay, records: List<RecordingTree.Record>, bootstrapRecords: Int) {
        assertTrue("[$label] the replay fed no input — fixture/wiring broken", replay.inputs.isNotEmpty())
        val deny = denyList(replay)
        assertTrue("[$label] harvested no store strings from the parse — deny-list would be vacuous", deny.isNotEmpty())

        val infoPlus = records.filter { it.priority >= Log.INFO }
        assertTrue("[$label] the run wrote NO INFO+ line — the gate would check an empty set", infoPlus.isNotEmpty())
        val handlerLines = records.drop(bootstrapRecords)
            .filter { it.priority >= Log.INFO && it.tag in E2ESessionReplay.HANDLER_TAGS }
        assertTrue(
            "[$label] no INFO+ line came from an effect handler after start — the effect layer did not run",
            handlerLines.isNotEmpty(),
        )

        for (record in infoPlus) {
            val leak = deny.firstOrNull { record.message.contains(it, ignoreCase = true) }
            assertNull(
                "[$label] INFO+ leaked raw store string '$leak': [${record.priority}/${record.tag}] ${record.message}",
                leak,
            )
            assertNull(
                "[$label] INFO+ line hit a sensitive marker: [${record.priority}/${record.tag}] ${record.message}",
                SensitiveTextMarkers.findMarker(record.message),
            )
        }
    }
}
