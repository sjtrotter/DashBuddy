package cloud.trotter.dashbuddy.pipeline.accessibility

import cloud.trotter.dashbuddy.core.data.capture.CaptureBus
import cloud.trotter.dashbuddy.core.data.capture.EnvelopeBuilder
import cloud.trotter.dashbuddy.core.data.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.ScreenDiffer
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenClassifier
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Window sub-pipe: merges content-changed and state-changed sources,
 * deduplicates, gates sensitive screens, classifies, captures, and
 * emits [Observation.Screen].
 */
@Singleton
class WindowSubPipe @Inject constructor(
    private val contentChangedPipeline: ContentChangedPipeline,
    private val stateChangedPipeline: StateChangedPipeline,
    private val differ: ScreenDiffer,
    private val sensitiveGate: SensitiveGate,
    private val classifier: ScreenClassifier,
    private val captureBus: CaptureBus,
) {
    companion object {
        const val PIPELINE_ID = "accessibility.window"
        private const val PLATFORM = "doordash"
    }

    fun output(): Flow<Observation.Screen> = merge(
        contentChangedPipeline.output(),
        stateChangedPipeline.output(),
    )
        .filter { node -> differ.hasChanged(node) }
        .filter { node ->
            val sensitive = sensitiveGate.isSensitive(node)
            if (sensitive) Timber.d("SensitiveGate: dropped sensitive screen")
            !sensitive
        }
        .map { tree ->
            val obs = classifier.classify(tree)

            val capture = EnvelopeBuilder.build(
                pipelineId = PIPELINE_ID,
                schema = UiNodeSchema,
                platform = PLATFORM,
                ruleId = obs.ruleId,
                classificationName = obs.target,
                payload = tree,
                contentHash = tree.structuralHash,
            )
            val captureId = captureBus.offer(
                captureId = capture.captureId,
                source = PIPELINE_ID,
                classification = obs.target,
                platform = PLATFORM,
                envelopeJson = capture.envelopeJson,
                contentHash = capture.contentHash,
            )

            obs.copy(captureId = captureId)
        }
}
