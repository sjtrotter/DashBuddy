package cloud.trotter.dashbuddy.pipeline.accessibility

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.data.capture.CaptureBus
import cloud.trotter.dashbuddy.core.data.capture.EnvelopeBuilder
import cloud.trotter.dashbuddy.core.data.capture.WindowContextDto
import cloud.trotter.dashbuddy.core.data.capture.schema.ClickCapturePayload
import cloud.trotter.dashbuddy.core.data.capture.schema.ClickContextSchema
import cloud.trotter.dashbuddy.core.data.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.core.data.settings.PlatformPreferencesRepository
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationIdentity
import cloud.trotter.dashbuddy.domain.pipeline.identity
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.windows_changed.WindowsChangedPipeline
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.pipeline.accessibility.mapper.toUiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified accessibility pipeline: merges screen and click event sources,
 * classifies through the rule engine, captures unique observations for replay,
 * and forwards only known, non-sensitive observations to the state machine.
 *
 * Flow: raw events → PipelineEvent → classify → gate sensitive → capture
 * (identity-based dedup) → gate unknown → state machine.
 */
@Singleton
class AccessibilityPipeline @Inject constructor(
    private val contentChangedPipeline: ContentChangedPipeline,
    private val stateChangedPipeline: StateChangedPipeline,
    private val windowsChangedPipeline: WindowsChangedPipeline,
    private val source: AccessibilitySource,
    private val classifier: ObservationClassifier,
    private val captureBus: CaptureBus,
    private val platformPreferences: PlatformPreferencesRepository,
) {
    companion object {
        const val SCREEN_PIPELINE_ID = "accessibility.window"
        const val CLICK_PIPELINE_ID = "accessibility.click"
    }

    /** Last emitted observation identity — for post-classification dedup. */
    private var lastIdentity: ObservationIdentity? = null

    /** Cached enabled platforms — updated reactively from preferences. */
    @Volatile
    private var enabledPlatforms: Set<Platform> = Platform.entries.toSet()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            platformPreferences.enabledPlatforms.collect { platforms ->
                enabledPlatforms = platforms
            }
        }
    }

    // ── Source flows ────────────────────────────────────────────────────

    private fun screenEvents(): Flow<PipelineEvent.Screen> = merge(
        contentChangedPipeline.output(),
        stateChangedPipeline.output(),
        windowsChangedPipeline.output(),
    ).map { snapshot ->
        PipelineEvent.Screen(
            timestamp = System.currentTimeMillis(),
            tree = snapshot.tree,
            snapshot = snapshot,
            packageName = snapshot.packageName,
        )
    }

    private fun clickEvents(): Flow<PipelineEvent.Click> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED }
        .mapNotNull { event ->
            val sourceNode = event.source ?: return@mapNotNull null
            val node = sourceNode.toUiNode() ?: return@mapNotNull null
            PipelineEvent.Click(
                timestamp = System.currentTimeMillis(),
                node = node,
                packageName = event.packageName?.toString(),
            )
        }

    // ── Main pipeline ──────────────────────────────────────────────────

    fun output(): Flow<Observation> = merge(screenEvents(), clickEvents())
        // Classify through the unified rule engine
        .map { event -> classifier.classify(event) to event }

        // Gate: drop sensitive/noise observations (pledge: never store or forward)
        .filter { (obs, _) ->
            val parsed = (obs as Observation.FlowObservation).parsed
            val isSensitive = parsed is ParsedFields.SensitiveFields
            val isNoise = parsed is ParsedFields.NoiseFields
            if (isSensitive) Timber.d("Sensitive gate: dropped %s", obs.target)
            if (isNoise) Timber.v("Noise gate: dropped %s", obs.target)
            !isSensitive && !isNoise
        }

        // Gate: drop observations from disabled platforms (defense-in-depth)
        .filter { (_, event) ->
            val pkg = when (event) {
                is PipelineEvent.Screen -> event.packageName
                is PipelineEvent.Click -> event.packageName
                else -> null
            }
            val platform = Platform.fromPackage(pkg)
            platform == Platform.Unknown || platform in enabledPlatforms
        }

        // Dedup + Capture: write unique observations to disk, skip duplicates
        .mapNotNull { (obs, event) ->
            val identity = obs.identity()
            if (identity == lastIdentity) {
                Timber.v("Dedup: skipped %s (same identity)", (obs as? Observation.FlowObservation)?.target)
                return@mapNotNull null
            }
            // Only update lastIdentity for known observations — UNKNOWN observations
            // get captured below but shouldn't reset dedup state, otherwise the next
            // known screen after an UNKNOWN re-forwards even if nothing changed.
            val target = (obs as? Observation.FlowObservation)?.target
            if (target != "UNKNOWN") lastIdentity = identity
            captureObservation(obs, event)
        }

        // Gate: don't forward UNKNOWN observations to state machine
        .filter { obs ->
            val target = (obs as? Observation.FlowObservation)?.target
            val isUnknown = target == "UNKNOWN"
            if (isUnknown) Timber.v("Unknown gate: captured but not forwarding %s", target)
            !isUnknown
        }

    // ── Capture ────────────────────────────────────────────────────────

    private fun captureObservation(obs: Observation, event: PipelineEvent): Observation {
        val flowObs = obs as Observation.FlowObservation
        // Derive platform from the source package, not from the matched rule
        val eventPackage = when (event) {
            is PipelineEvent.Screen -> event.packageName
            is PipelineEvent.Click -> event.packageName
            else -> null
        }
        val platform = Platform.fromPackage(eventPackage).wire

        return when (event) {
            is PipelineEvent.Screen -> {
                val winCtx = event.snapshot.windowContext?.let { wc ->
                    WindowContextDto(
                        windowId = wc.windowId,
                        windowType = wc.windowType,
                        windowTitle = wc.windowTitle,
                        windowLayer = wc.windowLayer,
                        isActive = wc.isActive,
                        isFocused = wc.isFocused,
                        totalWindowCount = wc.totalWindowCount,
                    )
                }
                val capture = EnvelopeBuilder.build(
                    pipelineId = SCREEN_PIPELINE_ID,
                    schema = UiNodeSchema,
                    platform = platform,
                    ruleId = flowObs.ruleId,
                    classificationName = flowObs.target,
                    payload = event.tree,
                    contentHash = event.tree.stableHash,
                    metadata = flowObs.metadata,
                    windowContext = winCtx,
                )
                val captureId = captureBus.offer(
                    captureId = capture.captureId,
                    source = SCREEN_PIPELINE_ID,
                    classification = flowObs.target,
                    platform = platform,
                    envelopeJson = capture.envelopeJson,
                    contentHash = capture.contentHash,
                )
                Timber.d(
                    "Captured screen: target=%s  ruleId=%s  captured=%s",
                    flowObs.target, flowObs.ruleId, captureId != null,
                )
                (obs as Observation.Screen).copy(captureId = captureId)
            }

            is PipelineEvent.Click -> {
                val clickPayload = ClickCapturePayload(
                    node = event.node,
                    screenTarget = classifier.lastScreenTarget,
                )
                val capture = EnvelopeBuilder.build(
                    pipelineId = CLICK_PIPELINE_ID,
                    schema = ClickContextSchema,
                    platform = platform,
                    ruleId = flowObs.ruleId,
                    classificationName = flowObs.target,
                    payload = clickPayload,
                    contentHash = event.node.structuralHash,
                    metadata = flowObs.metadata,
                )
                val captureId = captureBus.offer(
                    captureId = capture.captureId,
                    source = CLICK_PIPELINE_ID,
                    classification = flowObs.target,
                    platform = platform,
                    envelopeJson = capture.envelopeJson,
                    contentHash = capture.contentHash,
                )
                Timber.d(
                    "Captured click: target=%s  ruleId=%s  captured=%s",
                    flowObs.target, flowObs.ruleId, captureId != null,
                )
                (obs as Observation.Click).copy(captureId = captureId)
            }

            is PipelineEvent.Notification -> obs // not handled here
        }
    }
}
