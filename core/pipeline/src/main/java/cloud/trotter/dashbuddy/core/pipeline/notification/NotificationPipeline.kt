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
        // Dedup + Capture: known notifications dedup by identity, mixed with
        // content hash so two distinct arrivals sharing a parse-less rule's
        // constant identity aren't collapsed into one (#619); UNKNOWN by
        // content hash in a rolling seen-set (#360). `isOngoing` notifications
        // (e.g. `dash_status_ongoing`, the persistent "still dashing" status
        // heartbeat) opt OUT of content-mixing (#619 V3 precaution): it
        // reposts constantly and whether its body churns per repost (e.g. a
        // live counter) is unconfirmed against real captures — mixing there
        // could turn a benign repost into per-repost NOTIFICATION_RECEIVED
        // spam, so it keeps the old pure-identity dedup instead. This does
        // NOT affect UNKNOWN suppression, which always reads `raw.contentHash`
        // directly regardless of `isOngoing`.
        .mapNotNull { (obs, raw) ->
            if (!frameGate.admit(obs, raw.contentHash, mixNotificationContent = !raw.isOngoing)) {
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
