package cloud.trotter.dashbuddy.pipeline.accessibility

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.data.capture.CaptureBus
import cloud.trotter.dashbuddy.core.data.capture.EnvelopeBuilder
import cloud.trotter.dashbuddy.core.data.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked.ClickClassifier
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.pipeline.accessibility.mapper.toUiNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Click sub-pipe: filters TYPE_VIEW_CLICKED events, classifies the
 * tapped node, captures, and emits [Observation.Click].
 */
@Singleton
class ClickSubPipe @Inject constructor(
    private val source: AccessibilitySource,
    private val classifier: ClickClassifier,
    private val captureBus: CaptureBus,
) {
    companion object {
        const val PIPELINE_ID = "accessibility.click"
    }

    fun output(): Flow<Observation.Click> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED }
        .mapNotNull { event ->
            val sourceNode = event.source ?: return@mapNotNull null
            val node = sourceNode.toUiNode() ?: return@mapNotNull null
            val obs = classifier.classify(node)
            val platform = Platform.fromRuleId(obs.ruleId).wire
            Timber.d("Click Event: ${obs.target}")

            val capture = EnvelopeBuilder.build(
                pipelineId = PIPELINE_ID,
                schema = UiNodeSchema,
                platform = platform,
                ruleId = obs.ruleId,
                classificationName = obs.target,
                payload = node,
                contentHash = node.structuralHash,
                metadata = obs.metadata,
            )
            val captureId = captureBus.offer(
                captureId = capture.captureId,
                source = PIPELINE_ID,
                classification = obs.target,
                platform = platform,
                envelopeJson = capture.envelopeJson,
                contentHash = capture.contentHash,
            )

            obs.copy(captureId = captureId)
        }
}
