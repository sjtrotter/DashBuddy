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
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.ParsedFields
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
     * Loads every SCREEN `*.json` `CaptureEnvelope` under `src/test/resources/<pathFromResources>`,
     * recovering the envelope's capture `timestamp` / `platform` / `captureId`, and sorts the
     * frames into capture-time order. The `UiNode` tree itself is decoded by the existing
     * [TestResourceLoader.loadNode] (same `payload` path the rest of the corpus uses).
     *
     * CLICK envelopes in the same dir are skipped (their `payload` is `{node, screenTarget}`, not a
     * bare node) — load them with [loadClickFrame] for click injection ([reduceMixed]).
     */
    fun loadSession(pathFromResources: String): List<ReplayFrame> {
        val dir = File("src/test/resources/$pathFromResources")
        require(dir.isDirectory) { "Not a session directory: ${dir.absolutePath}" }
        return dir.listFiles { _, name -> name.endsWith(".json") }
            ?.mapNotNull { file ->
                val root = json.parseToJsonElement(file.readText()).jsonObject
                // A click capture's payload object carries "screenTarget"; a screen capture's payload
                // IS the UiNode. Skip clicks so loadNode never sees a non-node payload.
                val payload = root["payload"]?.jsonObject
                if (payload != null && payload.containsKey("screenTarget")) return@mapNotNull null
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

    /** One replayed step: the source frame (null for an injected synthetic obs), what it recognized
     *  as, the state after, and the events it emitted. */
    data class ReplayStep(
        val frame: ReplayFrame?,
        val observation: Observation,
        val stateAfter: AppState,
        val events: List<AppEvent>,
    )

    /**
     * Level B — fold the recognized observations through the REAL [StateMachine] and return a
     * per-frame step trace: each frame's observation, the `AppState` after it, and the `AppEvent`s
     * it emitted (the same events that land in the db `app_events` log). This is the shared spine of
     * the automated tests, the JVM trace, and the on-device review screen.
     *
     * Screen-only: a [ReplayFrame] list drives only Screen observations. The Job + its pre-created
     * dropoff subtasks form from the screen-flow transitions (OfferPresented→task flow), but the
     * offer logs `OFFER_TIMEOUT` (the `OFFER_ACCEPTED`/`OFFER_DECLINED` outcome needs a CLICK that
     * records the intent), and a session that goes quiet after the receipt never commits the retire
     * grace (it needs a `GRACE_COMMIT` timer). To drive a full accept→pickup→dropoff→complete chain,
     * use [reduceMixed] with injected [ClickInput]/[TimeoutInput].
     */
    fun reduce(frames: List<ReplayFrame>): List<ReplayStep> = reduceMixed(frames.map { ScreenInput(it) })

    /** Convenience: load + reduce in one call. */
    fun reduce(pathFromResources: String): List<ReplayStep> = reduce(loadSession(pathFromResources))

    // =====================================================================================
    // Level B with injection — clicks + timers (so a real-capture replay can drive a full
    // accept→pickup→dropoff→complete chain through the real StateMachine, not just screens).
    // =====================================================================================

    /**
     * One input to [reduceMixed]. [atMs] is the capture/inject time; the stream is folded in [atMs]
     * order, so ordering is deterministic and `obs.timestamp`-driven (no wall clock).
     */
    sealed interface ReplayInput {
        val atMs: Long
    }

    /** A real screen capture frame — classified through the production screen rules. */
    data class ScreenInput(val frame: ReplayFrame) : ReplayInput {
        override val atMs: Long get() = frame.capturedAtMs
    }

    /**
     * A real CLICK capture — classified through the production click rules. In [reduceMixed] the
     * preceding screen frame primes the classifier's last-screen-target cache (for `screenIs` click
     * rules); [screenTarget] is carried for completeness/debugging.
     */
    data class ClickInput(
        val node: UiNode,
        val screenTarget: String?,
        val wire: String,
        override val atMs: Long,
    ) : ReplayInput

    /** A synthetic timer (e.g. `GRACE_COMMIT`) — folded as an [Observation.Timeout]. */
    data class TimeoutInput(
        val type: TimeoutType,
        val platform: Platform,
        override val atMs: Long,
    ) : ReplayInput

    /**
     * A pre-built observation injected verbatim — for a fully synthetic click/decline (or a future
     * eval loopback) when no real capture exists, e.g. a stack window with no click captures.
     */
    data class RawInput(val observation: Observation, override val atMs: Long) : ReplayInput

    /**
     * Load a real CLICK capture as a [ClickInput] — a click envelope's `payload` is
     * `{node, screenTarget}` (not a bare node), so the screen [loadSession] path can't read it.
     */
    fun loadClickFrame(pathFromResources: String): ClickInput {
        val root = json.parseToJsonElement(File("src/test/resources/$pathFromResources").readText()).jsonObject
        val payload = root.getValue("payload").jsonObject
        return ClickInput(
            node = TestResourceLoader.nodeFromElement(payload.getValue("node")),
            screenTarget = payload["screenTarget"]?.jsonPrimitive?.contentOrNull,
            wire = root["platform"]?.jsonPrimitive?.contentOrNull ?: Platform.DoorDash.wire,
            atMs = root["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    /**
     * A fully synthetic offer-accept/decline click as a [RawInput] — when no real click capture
     * exists. Must be injected while flow is OfferPresented and strictly BEFORE the screen frame
     * that pops the offer, so `FlowRegionStepper.handleOfferClick` records the intent and the later
     * pop logs `OFFER_ACCEPTED`/`OFFER_DECLINED` (not `OFFER_TIMEOUT`). [intent] is an
     * `OfferIntent.ACCEPT`/`DECLINE` constant.
     */
    fun syntheticOfferClick(intent: String, atMs: Long, wire: String = Platform.DoorDash.wire): RawInput =
        RawInput(
            Observation.Click(
                timestamp = atMs,
                captureId = null,
                ruleId = "$wire.click.$intent",
                metadata = ReplayMetadata.EMPTY,
                flow = null,
                modeHint = null,
                parsed = ParsedFields.ClickFields(intent = intent),
                target = intent,
                screenTarget = "offer_popup",
            ),
            atMs = atMs,
        )

    /** A `GRACE_COMMIT` timeout scoped to [platform], at [atMs] — must be strictly greater than the
     *  armed retire-grace deadline so lazy expiry commits the dropoff completion. */
    fun graceCommit(atMs: Long, platform: Platform = Platform.DoorDash): TimeoutInput =
        TimeoutInput(TimeoutType.GRACE_COMMIT, platform, atMs)

    /** A `MODE_RESUME_COMMIT` timeout scoped to [platform], at [atMs] (#605) — must be strictly
     *  greater than the armed resume-grace deadline so lazy expiry commits the Paused→Online resume. */
    fun modeResumeCommit(atMs: Long, platform: Platform = Platform.DoorDash): TimeoutInput =
        TimeoutInput(TimeoutType.MODE_RESUME_COMMIT, platform, atMs)

    /**
     * Level B, heterogeneous — fold a timestamp-ordered mix of real screen/click captures and
     * synthetic click/timeout observations through the REAL [StateMachine]. ONE classifier instance
     * handles both screens and clicks, so a preceding screen primes `lastScreenTarget` for the
     * following click naturally (no reflection). Every observation is re-stamped with its input
     * [ReplayInput.atMs] (the classifier stamps wall-clock `eventNow`), keeping the fold
     * deterministic.
     */
    fun reduceMixed(inputs: List<ReplayInput>): List<ReplayStep> {
        val machine = StateMachine(
            flowStepper = FlowRegionStepper(),
            platformStepper = PlatformRegionStepper(),
            crossPlatformStepper = CrossPlatformRegionStepper(),
            transitionPolicy = TransitionPolicy(),
            effectMap = EffectMap(),
        )
        val classifier = mixedClassifier()
        var state = AppState()
        return inputs.sortedBy { it.atMs }.map { input ->
            val frame = (input as? ScreenInput)?.frame
            val obs: Observation = when (input) {
                is ScreenInput -> {
                    val f = input.frame
                    val pkg = Platform.entries.firstOrNull { it.wire == f.wire }?.packageName
                    classifier.classify(
                        PipelineEvent.Screen(
                            timestamp = f.capturedAtMs,
                            tree = f.node,
                            snapshot = TreeSnapshot(f.node, TreeSnapshot.Source.WINDOWS_CHANGED, packageName = pkg),
                            packageName = pkg,
                        ),
                    ).copy(timestamp = f.capturedAtMs)
                }
                is ClickInput -> {
                    val pkg = Platform.entries.firstOrNull { it.wire == input.wire }?.packageName
                    classifier.classify(
                        PipelineEvent.Click(timestamp = input.atMs, node = input.node, packageName = pkg),
                    ).copy(timestamp = input.atMs)
                }
                is TimeoutInput -> Observation.Timeout(
                    timestamp = input.atMs,
                    type = input.type,
                    targetPlatform = input.platform,
                )
                is RawInput -> input.observation
            }
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

    /** A readable per-step trace (JVM): `frame → recognized obs → emitted events`. */
    fun trace(steps: List<ReplayStep>): String = buildString {
        steps.forEachIndexed { i, s ->
            val events = s.events.joinToString { it.type.name }.ifEmpty { "—" }
            val label = (s.observation as? Observation.FlowObservation)?.target
                ?: (s.observation as? Observation.Timeout)?.type?.name
                ?: "UNKNOWN"
            val source = s.frame?.file?.take(40) ?: "<injected>"
            appendLine("[%2d] %-40s %-22s → %s".format(i, source, label, events))
        }
    }

    /**
     * A classifier wired to the **production** screen rules with no Android Context — the same
     * mock wiring [ClickClassifierTest] uses for the click path. Level A ([replayRecognition]) is
     * screen-only, so this wires only the screen ruleset.
     */
    private fun screenClassifier(): ObservationClassifier = ObservationClassifier(
        mock<JsonRuleInterpreter> { on { screenRuleset } doReturn TestRulesetFactory.screenRuleset },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
    )

    /**
     * A classifier wired to **both** the production screen and click rulesets — one instance, so a
     * screen classification updates `lastScreenTarget` for the next click ([reduceMixed]).
     */
    private fun mixedClassifier(): ObservationClassifier = ObservationClassifier(
        mock<JsonRuleInterpreter> {
            on { screenRuleset } doReturn TestRulesetFactory.screenRuleset
            on { clickRuleset } doReturn TestRulesetFactory.clickRuleset
        },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
    )
}
