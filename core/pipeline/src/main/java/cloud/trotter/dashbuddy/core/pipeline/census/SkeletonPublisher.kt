package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.capture.CensusSink
import cloud.trotter.dashbuddy.domain.census.contract.KindClassifier
import cloud.trotter.dashbuddy.domain.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.census.contract.UiSkeletonDto
import cloud.trotter.dashbuddy.domain.census.contract.UiSkeletonNodeDto
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * ADR-0011 publisher (#1146): UNKNOWN branch, after `FrameGate.admit` and `captureScreen` so the
 * stamped `captureId` is the pairing key, before the terminal UNKNOWN filter.
 * Screens only in v1 (clicks and notification bodies are out of scope).
 * Inert in the field: every variant binds `NoOpCensusSink` until M2/M3.
 */
@Singleton
class SkeletonPublisher internal constructor(
    private val sink: CensusSink,
    private val stats: PipelineStats,
    private val zoneId: ZoneId,
) {
    @Inject constructor(sink: CensusSink, stats: PipelineStats) : this(sink, stats, ZoneId.systemDefault())

    /** One WARN per publisher; the publisher is a Hilt @Singleton, so per process in the app (#1171 review: an instance field, not a JVM static, keeps tests isolated). */
    private val warned = AtomicBoolean()

    /** Post-admission, UNKNOWN screens only; fail-OPEN — nothing here may ever cost a frame (#1146). */
    fun publish(obs: Observation.Screen, event: PipelineEvent.Screen) {
        if (obs.target != UNKNOWN_TARGET) return

        try {
            // #1171 review (Astra): a sink's enablement getter is itself a seam a future binding can break;
            // it is read INSIDE the fail-open try, after the free UNKNOWN check.
            if (!sink.isEnabled) return
            val platform = Platform.fromPackage(event.packageName)
            // #1171 review: the disabled-platform gate deliberately ADMITS Platform.Unknown, but ADR-0011 scopes
            // the census to PLATFORM screens — an unattributable package is refused before any build (fail closed).
            if (platform == Platform.Unknown) {
                stats.onCensusUnattributedPlatform()
                return
            }
            val day = Instant.ofEpochMilli(obs.timestamp).atZone(zoneId).toLocalDate()
            when (val outcome = SkeletonBuilder.outcome(
                event.tree,
                event.snapshot.windowContext?.windowTitle,
                obs.metadata,
                platform,
                day,
            )) {
                is Outcome.Built -> {
                    val (hashed, withheld) = tokenCounts(outcome.skeleton)
                    stats.onCensusSkeleton(hashed, withheld)
                    val accepted = sink.offer(CensusRecord(
                        platform = platform,
                        fingerprint = outcome.skeleton.fingerprint,
                        skeletonJson = outcome.json,
                        itemBytes = outcome.itemBytes,
                        captureId = obs.captureId,
                    ))
                    if (!accepted) stats.onCensusSinkRefused()
                }
                is Outcome.Refused -> stats.onCensusRefused(outcome.reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            stats.onCensusPublishFailure()
            if (warned.compareAndSet(false, true)) {
                Timber.tag("Census").w(
                    "census publisher failed (%s); frame unaffected",
                    t.javaClass.simpleName,
                )
            }
        }
    }

}

/** Counts every node text slot and the window title, without retaining token content (#1146). */
internal fun tokenCounts(skeleton: UiSkeletonDto): Pair<Int, Int> {
    var hashed = 0
    var withheld = 0
    fun count(slot: TextSlot) {
        if (slot.h != null) hashed++
        if (slot.kind == KindClassifier.WITHHELD) withheld++
    }
    fun walk(node: UiSkeletonNodeDto) {
        node.text.values.forEach { count(it) }
        node.children.forEach { walk(it) }
    }
    walk(skeleton.root)
    skeleton.windowTitle?.let { count(it) }
    return hashed to withheld
}
