package cloud.trotter.dashbuddy.core.pipeline.notification

import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.EnvelopeBuilder
import cloud.trotter.dashbuddy.domain.capture.schema.RawNotificationSchema
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationIdentity
import cloud.trotter.dashbuddy.domain.pipeline.identity
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.notification.input.NotificationSource
import cloud.trotter.dashbuddy.core.pipeline.notification.mapper.toDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification pipeline: maps StatusBarNotification → classified observation.
 * Applies the same classify → capture → gate pattern as AccessibilityPipeline.
 */
@Singleton
class NotificationPipeline @Inject constructor(
    private val source: NotificationSource,
    private val filter: NotificationFilter,
    private val classifier: ObservationClassifier,
    private val captureBus: CaptureBus,
    private val platformPreferences: PlatformPreferences,
) {
    companion object {
        const val PIPELINE_ID = "notification"
    }

    private var lastIdentity: ObservationIdentity? = null

    fun output(): Flow<Observation.Notification> = source.events
        .mapNotNull { sbn -> sbn.toDomain() }
        .filter { raw -> filter.isRelevant(raw) }
        .map { raw ->
            val event = PipelineEvent.Notification(raw.postTime, raw)
            val obs = classifier.classify(event) as Observation.Notification
            obs to raw
        }
        // Gate: drop noise observations (known-irrelevant, never capture or forward)
        .filter { (obs, _) ->
            val isNoise = obs.parsed is ParsedFields.NoiseFields
            if (isNoise) Timber.v("Noise gate: dropped notification %s", obs.target)
            !isNoise
        }
        // Gate: drop observations from disabled platforms (defense-in-depth)
        .filter { (_, raw) ->
            val platform = Platform.fromPackage(raw.packageName)
            platform == Platform.Unknown ||
                platform in platformPreferences.enabledPlatforms.value
        }
        // Dedup + Capture
        .mapNotNull { (obs, raw) ->
            val identity = obs.identity()
            if (identity == lastIdentity) return@mapNotNull null
            if (obs.target != "UNKNOWN") lastIdentity = identity

            // Derive platform from the source package, not from the matched rule
            val platform = Platform.fromPackage(raw.packageName).wire
            val capture = EnvelopeBuilder.build(
                pipelineId = PIPELINE_ID,
                schema = RawNotificationSchema,
                platform = platform,
                ruleId = obs.ruleId,
                classificationName = obs.target,
                payload = raw,
                contentHash = raw.contentHash,
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
        // Gate: don't forward UNKNOWN to state machine
        .filter { obs ->
            val isUnknown = obs.target == "UNKNOWN"
            if (isUnknown) Timber.v("Notification: captured but not forwarding UNKNOWN")
            !isUnknown
        }
}
