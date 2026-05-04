package cloud.trotter.dashbuddy.pipeline.accessibility

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.data.capture.CaptureBus
import cloud.trotter.dashbuddy.core.data.capture.EnvelopeBuilder
import cloud.trotter.dashbuddy.core.data.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationIdentity
import cloud.trotter.dashbuddy.domain.pipeline.identity
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.pipeline.accessibility.mapper.toUiNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
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
    private val source: AccessibilitySource,
    private val classifier: ObservationClassifier,
    private val captureBus: CaptureBus,
) {
    companion object {
        const val SCREEN_PIPELINE_ID = "accessibility.window"
        const val CLICK_PIPELINE_ID = "accessibility.click"
    }

    /** Last emitted observation identity — for post-classification dedup. */
    private var lastIdentity: ObservationIdentity? = null

    // ── Source flows ────────────────────────────────────────────────────

    private fun screenEvents(): Flow<PipelineEvent.Screen> = merge(
        contentChangedPipeline.output(),
        stateChangedPipeline.output(),
    ).map { snapshot ->
        PipelineEvent.Screen(System.currentTimeMillis(), snapshot.tree, snapshot)
    }

    private fun clickEvents(): Flow<PipelineEvent.Click> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED }
        .mapNotNull { event ->
            val sourceNode = event.source ?: return@mapNotNull null
            val node = sourceNode.toUiNode() ?: return@mapNotNull null
            PipelineEvent.Click(System.currentTimeMillis(), node)
        }

    // ── Main pipeline ──────────────────────────────────────────────────

    fun output(): Flow<Observation> = merge(screenEvents(), clickEvents())
        // Classify through the unified rule engine
        .map { event -> classifier.classify(event) to event }

        // Gate: drop sensitive observations (pledge: never store or forward)
        .filter { (obs, _) ->
            val isSensitive = (obs as Observation.FlowObservation).parsed is ParsedFields.SensitiveFields
            if (isSensitive) Timber.d("Sensitive gate: dropped %s", obs.target)
            !isSensitive
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
        val platform = Platform.fromRuleId(flowObs.ruleId).wire

        return when (event) {
            is PipelineEvent.Screen -> {
                val capture = EnvelopeBuilder.build(
                    pipelineId = SCREEN_PIPELINE_ID,
                    schema = UiNodeSchema,
                    platform = platform,
                    ruleId = flowObs.ruleId,
                    classificationName = flowObs.target,
                    payload = event.tree,
                    contentHash = event.tree.stableHash,
                    metadata = flowObs.metadata,
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
                val capture = EnvelopeBuilder.build(
                    pipelineId = CLICK_PIPELINE_ID,
                    schema = UiNodeSchema,
                    platform = platform,
                    ruleId = flowObs.ruleId,
                    classificationName = flowObs.target,
                    payload = event.node,
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
