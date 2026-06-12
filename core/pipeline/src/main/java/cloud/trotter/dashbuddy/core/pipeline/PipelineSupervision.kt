package cloud.trotter.dashbuddy.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import timber.log.Timber

/**
 * Supervision for the hot sensing upstream (#430).
 *
 * The whole sensing layer — both sensor pipelines — runs as ONE shared
 * upstream coroutine (`PipelineV2.events`). Without supervision, a single
 * uncaught exception anywhere in that chain (mapping, classification,
 * hashing, envelope building) cancels it permanently: the app looks alive
 * but observes nothing, with no detection.
 *
 * This resubscribes the upstream after a crash, with linear backoff capped
 * at [MAX_BACKOFF_MULTIPLIER] × [RESTART_BASE_DELAY_MS]. Re-collection is
 * safe by construction: the raw sources are hot SharedFlows that never
 * complete, and all dedup state (FrameGate, suppressors, capture dedup)
 * lives in singletons that survive the resubscribe. Retries are unbounded —
 * sensing must never stay dead — and each restart is counted and logged
 * loudly with a stats summary so a crash-looping pipeline is visible.
 *
 * Cancellation is never retried: a cancelled scope propagates normally.
 */
internal fun <T> Flow<T>.supervised(
    stats: PipelineStats,
    tag: String,
    baseDelayMs: Long = RESTART_BASE_DELAY_MS,
): Flow<T> = retryWhen { cause, attempt ->
    if (cause is CancellationException) throw cause
    val restartNumber = stats.onPipelineRestart()
    Timber.e(
        cause,
        "Pipeline '%s' crashed (restart #%d) — resubscribing after backoff",
        tag, restartNumber,
    )
    stats.logSummary("restart")
    delay(baseDelayMs * (attempt + 1).coerceAtMost(MAX_BACKOFF_MULTIPLIER))
    true
}

/** Base delay before the first resubscribe. */
internal const val RESTART_BASE_DELAY_MS = 1_000L

/** Backoff cap: delay never exceeds this multiple of the base. */
internal const val MAX_BACKOFF_MULTIPLIER = 5L
