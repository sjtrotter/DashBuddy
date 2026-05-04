package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.core.data.capture.CaptureBus
import cloud.trotter.dashbuddy.core.data.capture.EnvelopeBuilder
import cloud.trotter.dashbuddy.core.data.capture.schema.RawNotificationSchema
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.pipeline.notification.input.NotificationSource
import cloud.trotter.dashbuddy.pipeline.notification.mapper.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification sub-pipe: maps StatusBarNotification -> classified observation.
 * Now captures raw notification payloads to disk (previously silently dropped).
 */
@Singleton
class NotificationPipeline @Inject constructor(
    private val source: NotificationSource,
    private val filter: NotificationFilter,
    private val classifier: NotificationClassifier,
    private val captureBus: CaptureBus,
) {
    companion object {
        const val PIPELINE_ID = "notification"
    }

    fun output(): Flow<Observation.Notification> = source.events
        .mapNotNull { sbn -> sbn.toDomain() }
        .filter { raw -> filter.isRelevant(raw) }
        .map { raw ->
            val obs = classifier.classify(raw).copy(timestamp = raw.postTime)
            val platform = Platform.fromRuleId(obs.ruleId).wire

            val capture = EnvelopeBuilder.build(
                pipelineId = PIPELINE_ID,
                schema = RawNotificationSchema,
                platform = platform,
                ruleId = obs.ruleId,
                classificationName = obs.target,
                payload = raw,
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
