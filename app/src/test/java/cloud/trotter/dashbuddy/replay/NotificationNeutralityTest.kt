package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.census.NotificationCorpus
import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PlatformAppVersions
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.core.state.CrossPlatformRegionStepper
import cloud.trotter.dashbuddy.core.state.EffectMap
import cloud.trotter.dashbuddy.core.state.FlowRegionStepper
import cloud.trotter.dashbuddy.core.state.PlatformRegionStepper
import cloud.trotter.dashbuddy.core.state.StateMachine
import cloud.trotter.dashbuddy.core.state.TransitionPolicy
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.StateMachineContract
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingModeResume
import cloud.trotter.dashbuddy.domain.state.PendingWake
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.test.util.SessionReplay
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

/** #1224: recognition-only pushes preserve lifecycle through the real classifier and reducer.
 * Like FlowlessRecognitionNeutralityTest, this belongs outside AllMatchersSuite.
 */
class NotificationNeutralityTest {
    private val classifier = ObservationClassifier(
        mock<JsonRuleInterpreter> {
            on { notificationRuleset } doReturn TestRulesetFactory.notificationRuleset
        },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
        PlatformAppVersions.NONE,
    )

    private val machine = StateMachine(
        flowStepper = FlowRegionStepper(),
        platformStepper = PlatformRegionStepper(),
        crossPlatformStepper = CrossPlatformRegionStepper(),
        transitionPolicy = TransitionPolicy(),
        effectMap = EffectMap(),
    )

    private val informationalRules: List<JsonObject> by lazy {
        File(TestRulesetFactory.rulesDir).listFiles().orEmpty()
            .filter { it.extension == "json" }.sortedBy { it.name }
            .flatMap { file ->
                Json.parseToJsonElement(file.readText()).jsonObject["notifications"]
                    ?.jsonArray.orEmpty().map { it.jsonObject }
            }
            .filter { rule ->
                val intent = rule["intent"]?.jsonPrimitive?.content ?: rule.id.substringAfterLast('.')
                "state" !in rule && intent !in StateMachineContract.EFFECT_INTENTS
            }
    }

    private val fixtureNotifications: List<RawNotificationData> by lazy {
        File("src/test/resources/snapshots").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("notification") }
            .sortedBy { it.name }
            .flatMap { NotificationCorpus.discover(it) }
            .map { it.raw.copy(postTime = NOW) }
    }

    @Test
    fun `every stateless informational notification preserves platform lifecycle`() {
        assertTrue("expected at least 25 informational notification rules", informationalRules.size >= 25)
        val skipped = linkedMapOf<String, String>()
        for (rule in informationalRules) {
            val raw = try {
                matchingRaw(rule)
            } catch (unsupported: UnsupportedOperationException) {
                skipped[rule.id] = requireNotNull(unsupported.message)
                continue
            }
            val observation = classify(raw)
            assertEquals("${rule.id}: classifier must reach the intended rule", rule.id, observation.ruleId)
            assertEquals("${rule.id}: no flow", null, observation.flow)
            assertEquals("${rule.id}: no mode hint", null, observation.modeHint)
            val platform = requireNotNull(Platform.fromWire(rule.id.substringBefore('.')))
            assertEquals("${rule.id}: platform attribution", platform, observation.platform)

            for ((name, region) in startingRegions(platform)) {
                val before = AppState(
                    regions = Regions(
                        flow = FlowRegion(flow = Flow.Idle, activePlatform = platform),
                        platforms = mapOf(platform to region),
                    ),
                    timestamp = NOW - 1,
                )
                val transition = machine.step(before, observation)
                val after = transition.newState.regions.platforms.getValue(platform)
                val label = "${rule.id} from $name"
                assertEquals("$label: mode", region.mode, after.mode)
                assertEquals("$label: session", region.session, after.session)
                assertEquals("$label: pause deadline", region.pauseSafety?.deadline, after.pauseSafety?.deadline)
                assertEquals("$label: pause wake identity", region.pauseSafety, after.pauseSafety)
                assertEquals("$label: pending resume", region.pendingModeResume, after.pendingModeResume)
                assertEquals("$label: pending destructive", region.pendingDestructive, after.pendingDestructive)
                assertEquals("$label: active task", region.activeTask, after.activeTask)
                assertEquals("$label: active job", region.activeJob, after.activeJob)
                assertNoLifecycleTimers(label, transition.effects)
            }
        }
        // No current rule needs an exemption. Unsupported synthesis records its rule and reason;
        // extending this allowlist requires an explicit, bounded exception, never a silent skip.
        assertEquals("unsupported notification rules (id -> reason)", emptyMap<String, String>(), skipped)
    }

    @Test
    fun `dash paused push precedes the authoritative screen by eight seconds (#1090)`() {
        val id = "doordash.notification.dash_paused"
        val push = classify(matchingRaw(informationalRules.single { it.id == id }))
        assertEquals("$id: push recognition", id, push.ruleId)
        val idle = SessionReplay.loadSession("snapshots/waiting_for_offer")
            .single { it.file == "7416147f.json" }
            .copy(capturedAtMs = NOW - 1_000, wire = Platform.DoorDash.wire)
        val paused = SessionReplay.loadSession("snapshots/dash_paused")
            .single { it.file == "2026-09-28_11-58-56-779__doordash__accessibility.window__dash_paused__582112.json" }
            .copy(capturedAtMs = NOW + 8_000)
        val steps = SessionReplay.reduceMixed(
            listOf(
                SessionReplay.ScreenInput(idle),
                SessionReplay.RawInput(push, NOW),
                SessionReplay.ScreenInput(paused),
            ),
        )
        val online = steps[0].stateAfter.regions.platforms.getValue(Platform.DoorDash)
        assertEquals("$id: journey starts online", Mode.Online, online.mode)
        assertTrue("$id: journey starts with a session", online.session != null)
        val afterPush = steps[1].stateAfter.regions.platforms.getValue(Platform.DoorDash)
        assertEquals("$id: push leaves mode online", online.mode, afterPush.mode)
        assertEquals("$id: push preserves session", online.session, afterPush.session)
        assertEquals("$id: push cannot arm pause safety", null, afterPush.pauseSafety)
        assertNoLifecycleTimers(id, steps[1].effects)

        val screen = steps[2].observation as Observation.Screen
        assertEquals("$id: screen recognition", "doordash.screen.dash_paused", screen.ruleId)
        val remaining = requireNotNull((screen.parsed as ParsedFields.PausedFields).remainingMillis)
        assertTrue("$id: screen must carry a live countdown", remaining > 0)
        val expectedDeadline = NOW + 8_000 + remaining +
            TransitionPolicy().pauseTimeoutBufferMs(Platform.DoorDash)
        val afterScreen = steps[2].stateAfter.regions.platforms.getValue(Platform.DoorDash)
        assertEquals("$id: screen pauses", Mode.Paused, afterScreen.mode)
        assertEquals("$id: screen sets deadline", expectedDeadline, afterScreen.pauseSafety?.deadline)
        val pauseEvents = steps.flatMap { it.events }.filter { it.type == AppEventType.DASH_PAUSED }
        assertEquals("$id: exactly one screen-timed pause event", listOf(NOW + 8_000), pauseEvents.map { it.occurredAt })
        val arms = steps.flatMap { it.effects }.filterIsInstance<AppEffect.ScheduleTimeout>()
            .filter { it.type == TimeoutType.SESSION_PAUSED_SAFETY }
        assertEquals("$id: exactly one screen-owned safety arm", listOf(expectedDeadline), arms.map { it.deadlineMs })
        assertEquals("$id: safety arm platform", Platform.DoorDash, arms.single().platform)
        assertNoLifecycleTimers(id, steps.flatMap { it.effects }, setOf(TimeoutType.MODE_RESUME_COMMIT))
    }

    private fun startingRegions(platform: Platform): List<Pair<String, PlatformRegion>> {
        val online = PlatformRegion(
            platform = platform,
            mode = Mode.Online,
            session = Session(sessionId = "session", startedAt = NOW - 60_000),
            lastActedFlow = Flow.Idle,
        )
        val paused = online.copy(mode = Mode.Paused, pauseSafety = PendingWake(NOW + 60_000, wakeId = 1))
        val task = Task(taskId = "task", jobId = "job", phase = TaskPhase.DROPOFF, startedAt = NOW - 30_000)
        // All deadlines are live: lazy expiry of an already-due grace is legitimate on ANY input.
        val pending = paused.copy(
            pendingModeResume = PendingModeResume(NOW - 1_000, NOW + 7_000, wakeId = 2),
            pendingDestructive = PendingDestructive(
                kind = DestructiveKind.TASK_RETIRE,
                since = NOW - 1_000,
                deadline = NOW + 9_000,
                armedFromFlow = Flow.Idle,
                wakeId = 3,
            ),
            activeTask = task,
            activeJob = Job(
                jobId = "job", offerStoreHint = emptyList(), parentOfferHash = null,
                tasks = listOf(task), startedAt = task.startedAt,
            ),
            wakeSeq = 3,
        )
        return listOf("online idle" to online, "paused" to paused.copy(wakeSeq = 1), "pending resume and retire" to pending)
    }

    private fun assertNoLifecycleTimers(
        label: String,
        effects: List<AppEffect>,
        types: Set<TimeoutType> = setOf(TimeoutType.MODE_RESUME_COMMIT, TimeoutType.SESSION_PAUSED_SAFETY),
    ) {
        val forbidden = effects.filter {
            when (it) {
                is AppEffect.ScheduleTimeout -> it.type in types
                is AppEffect.CancelTimeout -> it.type in types
                else -> false
            }
        }
        assertEquals("$label: no lifecycle timer changes", emptyList<AppEffect>(), forbidden)
    }

    private fun classify(raw: RawNotificationData): Observation.Notification =
        classifier.classify(PipelineEvent.Notification(raw.postTime, raw))

    private fun matchingRaw(rule: JsonObject): RawNotificationData {
        fixtureNotifications.firstOrNull { classify(it).ruleId == rule.id }?.let { return it }
        if ("branches" in rule) throw UnsupportedOperationException("branch predicates need synthesis support")
        val platform = requireNotNull(Platform.fromWire(rule.id.substringBefore('.')))
        val fields = synthesize(rule.getValue("require").jsonObject)
        return RawNotificationData(
            title = fields["title"], text = fields["text"], bigText = null, tickerText = null,
            channelId = fields["channelId"], packageName = requireNotNull(platform.packageName),
            postTime = NOW, isClearable = true,
        )
    }

    /** Minimal witnesses for the shipped require predicates; the full classifier checks precedence. */
    private fun synthesize(predicate: JsonObject): Map<String, String> {
        predicate["all"]?.let { all ->
            return all.jsonArray.map { synthesize(it.jsonObject) }.fold(emptyMap<String, String>()) { left, right ->
                (left.keys + right.keys).associateWith { key ->
                    listOfNotNull(left[key], right[key]).joinToString(" ")
                }
            }
        }
        predicate["any"]?.let { return synthesize(it.jsonArray.first().jsonObject) }
        if (predicate.size != 1) throw UnsupportedOperationException("unsupported require: $predicate")
        val (key, value) = predicate.entries.single()
        val text = value.jsonPrimitive.content
        return when (key) {
            "titleContains", "titleEquals" -> mapOf("title" to text)
            "anyFieldContains" -> mapOf("text" to text)
            "channelIdContains", "channelIdEquals" -> mapOf("channelId" to text)
            "titleMatchesRegex" -> mapOf("title" to when (text) {
                "^Going to (?:\\D|$)" -> "Going to Restaurant"
                "^Going to \\d" -> "Going to 123 Example Street"
                "(Leave the order at|Meet at door for)" -> "Leave the order at door"
                "received a \\$\\d" -> "received a $5"
                else -> throw UnsupportedOperationException("no regex witness for $text")
            })
            else -> throw UnsupportedOperationException("unsupported require predicate: $key")
        }
    }

    private val JsonObject.id: String get() = getValue("id").jsonPrimitive.content

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
