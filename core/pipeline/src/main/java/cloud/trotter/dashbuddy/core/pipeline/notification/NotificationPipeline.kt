package cloud.trotter.dashbuddy.core.pipeline.notification

import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.identity
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.CaptureWriter
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.FrameGate
import cloud.trotter.dashbuddy.core.pipeline.passesContentGates
import cloud.trotter.dashbuddy.core.pipeline.notification.input.NotificationSource
import cloud.trotter.dashbuddy.core.pipeline.notification.mapper.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET

/**
 * Notification pipeline: maps StatusBarNotification → classified observation.
 * Applies the same classify → capture → gate pattern as AccessibilityPipeline.
 */
@Singleton
class NotificationPipeline @Inject constructor(
    private val source: NotificationSource,
    private val filter: NotificationFilter,
    private val classifier: ObservationClassifier,
    private val captureWriter: CaptureWriter,
    private val platformPreferences: PlatformPreferences,
    private val stats: PipelineStats,
) {
    companion object {
        const val PIPELINE_ID = "notification"
    }

    /** Identity dedup + content-bearing UNKNOWN suppression (#360). */
    private val frameGate = FrameGate()

    fun output(): Flow<Observation.Notification> = source.events
        .mapNotNull { sbn -> sbn.toDomain() }
        // Gate: drop everything until rulesets load (#432) — symmetric with
        // the accessibility pipeline.
        .filter { raw ->
            val ready = classifier.isReady
            if (!ready) stats.onDroppedAwaitingRules()
            ready
        }
        .filter { raw -> filter.isRelevant(raw) }
        .map { raw ->
            val event = PipelineEvent.Notification(raw.postTime, raw)
            classifier.classify(event) to raw
        }
        // Gate: drop sensitive/noise observations (pledge: never store or
        // forward) — the shared content gate, symmetric with the
        // accessibility pipeline (#399).
        .filter { (obs, _) ->
            val passes = passesContentGates(obs)
            if (!passes) stats.onContentGateDrop(obs.parsed)
            passes
        }
        // Gate: drop observations from disabled platforms (defense-in-depth)
        .filter { (_, raw) ->
            val platform = Platform.fromPackage(raw.packageName)
            val allowed = platform == Platform.Unknown ||
                platform in platformPreferences.enabledPlatforms.value
            if (!allowed) stats.onDisabledPlatformDrop()
            allowed
        }
        // Dedup + Capture: known notifications dedup by identity; UNKNOWN by
        // content hash in a rolling seen-set (#360).
        .mapNotNull { (obs, raw) ->
            if (!frameGate.admit(obs, raw.contentHash)) {
                stats.onDuplicateSuppressed()
                return@mapNotNull null
            }
            captureWriter.captureNotification(obs, raw)
        }
        // Gate: don't forward UNKNOWN to state machine
        .filter { obs ->
            val isUnknown = obs.target == UNKNOWN_TARGET
            if (isUnknown) {
                stats.onUnknownDropped()
                Timber.v("Notification: captured but not forwarding UNKNOWN")
            }
            !isUnknown
        }
        .onEach { stats.onForwarded() }
}
