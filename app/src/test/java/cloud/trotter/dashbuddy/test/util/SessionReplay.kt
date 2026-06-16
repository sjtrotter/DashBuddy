package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
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
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

/**
 * Session-replay harness — drives a *sequence* of real on-device captures through the
 * production recognition layer so a field-observed defect can be reproduced and asserted
 * as a red/green test, with no field dash required to validate a fix.
 *
 * This is **Level A** (recognition only): a real `CaptureEnvelope` sequence becomes a
 * `List<Observation>` via the *production* rule JSON ([TestRulesetFactory]). It uses no
 * steppers and no timers, so it is fully deterministic. It catches recognition-side
 * defects — e.g. a blank `offer_popup` frame being classified as a real offer (#498), or a
 * screen that should recognize but falls to UNKNOWN (#501).
 *
 * Level B (folding the Observation sequence through the real `StateMachine` to assert the
 * emitted event timeline against the db oracle) builds on top of this and is added
 * separately — it needs timer/grace synthesis and is documented in the harness design.
 *
 * Input lives under `app/src/test/resources/snapshots/sessions/<name>/`; each file is a
 * device `CaptureEnvelope` (top-level `payload` = the `UiNode` tree, plus `timestamp` /
 * `platform` / `captureId`).
 */
object SessionReplay {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** One replay input: a real capture with the envelope metadata [TestResourceLoader] discards. */
    data class ReplayFrame(
        val file: String,
        val node: UiNode,
        val capturedAtMs: Long,
        val wire: String,
        val captureId: String?,
    )

    /**
     * Loads every `*.json` `CaptureEnvelope` under `src/test/resources/<pathFromResources>`,
     * recovering the envelope's capture `timestamp` / `platform` / `captureId`, and sorts the
     * frames into capture-time order. The `UiNode` tree itself is decoded by the existing
     * [TestResourceLoader.loadNode] (same `payload` path the rest of the corpus uses).
     */
    fun loadSession(pathFromResources: String): List<ReplayFrame> {
        val dir = File("src/test/resources/$pathFromResources")
        require(dir.isDirectory) { "Not a session directory: ${dir.absolutePath}" }
        return dir.listFiles { _, name -> name.endsWith(".json") }
            ?.map { file ->
                val root = json.parseToJsonElement(file.readText()).jsonObject
                ReplayFrame(
                    file = file.name,
                    node = TestResourceLoader.loadNode(file),
                    capturedAtMs = root["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
                    wire = root["platform"]?.jsonPrimitive?.contentOrNull ?: Platform.Unknown.wire,
                    captureId = root["captureId"]?.jsonPrimitive?.contentOrNull,
                )
            }
            ?.sortedBy { it.capturedAtMs }
            ?: emptyList()
    }

    /**
     * Level A — classify each frame through the production screen ruleset, returning the
     * `Observation.Screen` sequence in capture order.
     *
     * Clock correction: [ObservationClassifier] stamps a Screen observation with
     * `System.currentTimeMillis()` (its `eventNow`), **not** the capture time, so each
     * returned observation is re-stamped with the frame's real `capturedAtMs` — the steppers
     * are `obs.timestamp`-driven, so the replayed time must be the captured time.
     */
    fun replayRecognition(frames: List<ReplayFrame>): List<Observation.Screen> {
        val classifier = screenClassifier()
        return frames.map { frame ->
            val pkg = Platform.entries.firstOrNull { it.wire == frame.wire }?.packageName
            val event = PipelineEvent.Screen(
                timestamp = frame.capturedAtMs,
                tree = frame.node,
                snapshot = TreeSnapshot(frame.node, TreeSnapshot.Source.WINDOWS_CHANGED, packageName = pkg),
                packageName = pkg,
            )
            classifier.classify(event).copy(timestamp = frame.capturedAtMs)
        }
    }

    /** Convenience: load + replay recognition in one call. */
    fun replayRecognition(pathFromResources: String): List<Observation.Screen> =
        replayRecognition(loadSession(pathFromResources))

    /** One replayed step: the frame, what it recognized as, the state after, and the events it emitted. */
    data class ReplayStep(
        val frame: ReplayFrame,
        val observation: Observation.Screen,
        val stateAfter: AppState,
        val events: List<AppEvent>,
    )

    /**
     * Level B — fold the recognized observations through the REAL [StateMachine] and return a
     * per-frame step trace: each frame's observation, the `AppState` after it, and the `AppEvent`s
     * it emitted (the same events that land in the db `app_events` log). This is the shared spine of
     * the automated tests, the JVM trace, and the on-device review screen.
     *
     * Screen-only caveat: offer accept/decline are driven by CLICK observations (EffectMap), which
     * the on-disk corpus rarely contains — so a screen-only replay resolves offers to `OFFER_TIMEOUT`.
     * Driving a full accept→pickup→dropoff chain needs synthetic click + timer observations (a later
     * phase of the harness).
     */
    fun reduce(frames: List<ReplayFrame>): List<ReplayStep> {
        val machine = StateMachine(
            flowStepper = FlowRegionStepper(),
            platformStepper = PlatformRegionStepper(),
            crossPlatformStepper = CrossPlatformRegionStepper(),
            transitionPolicy = TransitionPolicy(),
            effectMap = EffectMap(),
        )
        val observations = replayRecognition(frames)
        var state = AppState()
        return frames.zip(observations).map { (frame, obs) ->
            val transition = machine.step(state, obs)
            state = transition.newState
            ReplayStep(
                frame = frame,
                observation = obs,
                stateAfter = state,
                events = transition.effects.filterIsInstance<AppEffect.LogEvent>().map { it.event },
            )
        }
    }

    /** Convenience: load + reduce in one call. */
    fun reduce(pathFromResources: String): List<ReplayStep> = reduce(loadSession(pathFromResources))

    /** A readable per-step trace (JVM): `frame → recognized screen → emitted events`. */
    fun trace(steps: List<ReplayStep>): String = buildString {
        steps.forEachIndexed { i, s ->
            val events = s.events.joinToString { it.type.name }.ifEmpty { "—" }
            appendLine("[%2d] %-40s %-22s → %s".format(i, s.frame.file.take(40), s.observation.target ?: "UNKNOWN", events))
        }
    }

    /**
     * A classifier wired to the **production** screen rules with no Android Context — the same
     * mock wiring [ClickClassifierTest] uses for the click path.
     */
    private fun screenClassifier(): ObservationClassifier = ObservationClassifier(
        mock<JsonRuleInterpreter> { on { screenRuleset } doReturn TestRulesetFactory.screenRuleset },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
    )
}
