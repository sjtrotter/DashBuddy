package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.state.ParsedFields
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production observability for the sensing layer (#430).
 *
 * Before this existed, every gate decision, mapping failure, and pipeline
 * restart was invisible outside Timber verbose lines — "the app observes
 * nothing" and "the app is working" looked identical. These are cheap atomic
 * counters incremented at the existing decision points, with a one-line
 * summary logged every [SUMMARY_EVERY] forwarded observations and on every
 * supervised restart.
 *
 * Counting note: raw ingress drops (the sources' DROP_OLDEST overflow) are
 * not directly observable — `tryEmit` never fails under DROP_OLDEST — so
 * loss shows up as the delta between what the listener saw and what the
 * counters account for, not as its own counter.
 */
@Singleton
class PipelineStats @Inject constructor() {

    private val droppedSensitive = AtomicLong()
    private val droppedNoise = AtomicLong()
    private val droppedDisabledPlatform = AtomicLong()
    private val suppressedDuplicate = AtomicLong()
    private val droppedUnknown = AtomicLong()
    private val mappingFailures = AtomicLong()
    private val restarts = AtomicLong()
    private val forwarded = AtomicLong()
    private val droppedAwaitingRules = AtomicLong()
    private val scrubbedUnknownCaptures = AtomicLong()
    private val redactBackstopScrubs = AtomicLong()
    private val unknownCustomerScrubs = AtomicLong()
    private val notifRedactBackstopScrubs = AtomicLong()
    private val notifListenerConnects = AtomicLong()
    private val notifListenerDisconnects = AtomicLong()

    val droppedSensitiveCount: Long get() = droppedSensitive.get()
    val droppedNoiseCount: Long get() = droppedNoise.get()
    val droppedDisabledPlatformCount: Long get() = droppedDisabledPlatform.get()
    val suppressedDuplicateCount: Long get() = suppressedDuplicate.get()
    val droppedUnknownCount: Long get() = droppedUnknown.get()
    val mappingFailureCount: Long get() = mappingFailures.get()
    val restartCount: Long get() = restarts.get()
    val forwardedCount: Long get() = forwarded.get()
    val droppedAwaitingRulesCount: Long get() = droppedAwaitingRules.get()
    val scrubbedUnknownCaptureCount: Long get() = scrubbedUnknownCaptures.get()
    val redactBackstopScrubCount: Long get() = redactBackstopScrubs.get()
    val unknownCustomerScrubCount: Long get() = unknownCustomerScrubs.get()
    val notifRedactBackstopScrubCount: Long get() = notifRedactBackstopScrubs.get()
    val notifListenerConnectCount: Long get() = notifListenerConnects.get()
    val notifListenerDisconnectCount: Long get() = notifListenerDisconnects.get()

    /** A frame the shared content gate dropped (sensitive or noise, #399). */
    fun onContentGateDrop(parsed: ParsedFields) {
        if (parsed is ParsedFields.SensitiveFields) {
            droppedSensitive.incrementAndGet()
        } else {
            droppedNoise.incrementAndGet()
        }
    }

    fun onDisabledPlatformDrop() {
        droppedDisabledPlatform.incrementAndGet()
    }

    /** FrameGate rejected the frame (identity dedup / UNKNOWN suppression, #360). */
    fun onDuplicateSuppressed() {
        suppressedDuplicate.incrementAndGet()
    }

    /** UNKNOWN frame captured for triage but not forwarded to the state machine. */
    fun onUnknownDropped() {
        droppedUnknown.incrementAndGet()
    }

    /** A raw event whose node mapping threw — the event was dropped, not the pipeline. */
    fun onMappingFailure() {
        mappingFailures.incrementAndGet()
    }

    /** A frame dropped because no ruleset is loaded yet — the sensitive gate
     *  is rule-driven, so pre-rules frames are never classified or captured (#432). */
    fun onDroppedAwaitingRules() {
        droppedAwaitingRules.incrementAndGet()
    }

    /** An UNKNOWN capture dropped by the fail-closed text-marker backstop (#432). */
    fun onScrubbedUnknownCapture() {
        scrubbedUnknownCaptures.incrementAndGet()
    }

    /** A RECOGNIZED frame whose rule shipped an un-redacted customer marker; the
     *  node was scrubbed in the envelope by the #624 defense-in-depth backstop. */
    fun onRedactBackstopScrub() {
        redactBackstopScrubs.incrementAndGet()
    }

    /** An UNKNOWN screen carrying a customer-PII marker (a customer-bearing surface
     *  no rule recognized); the offending node was scrubbed in the envelope by the
     *  #806 UNKNOWN-screen customer backstop. Distinct from [onScrubbedUnknownCapture]
     *  (which DROPS the whole capture for the dasher's own sensitive screens). */
    fun onUnknownCustomerScrub() {
        unknownCustomerScrubs.incrementAndGet()
    }

    /** A RECOGNIZED NOTIFICATION whose rule shipped an un-redacted customer marker;
     *  the offending flat field was scrubbed in the envelope by the #632
     *  defense-in-depth notification backstop (the notif analogue of #624). */
    fun onNotifRedactBackstopScrub() {
        notifRedactBackstopScrubs.incrementAndGet()
    }

    /** The supervised upstream crashed and is resubscribing. Returns the restart ordinal. */
    fun onPipelineRestart(): Long = restarts.incrementAndGet()

    /** The system (re)bound the notification listener service (#731). Returns the running
     *  connect count for this process. */
    fun onNotifListenerConnected(): Long = notifListenerConnects.incrementAndGet()

    /** The system tore down the notification listener (#731) — opens an offer-miss window until
     *  reconnect, so this is a degradation, not a milestone. Returns the running disconnect count. */
    fun onNotifListenerDisconnected(): Long = notifListenerDisconnects.incrementAndGet()

    /** An observation was forwarded to the state machine. */
    fun onForwarded() {
        val n = forwarded.incrementAndGet()
        if (n % SUMMARY_EVERY == 0L) logSummary("periodic")
    }

    fun logSummary(reason: String) {
        Timber.i("PipelineStats[%s]: %s", reason, summary())
    }

    fun summary(): String =
        "forwarded=${forwarded.get()}" +
            " dupSuppressed=${suppressedDuplicate.get()}" +
            " unknownDropped=${droppedUnknown.get()}" +
            " sensitiveDropped=${droppedSensitive.get()}" +
            " noiseDropped=${droppedNoise.get()}" +
            " disabledPlatformDropped=${droppedDisabledPlatform.get()}" +
            " mappingFailures=${mappingFailures.get()}" +
            " awaitingRulesDropped=${droppedAwaitingRules.get()}" +
            " unknownScrubbed=${scrubbedUnknownCaptures.get()}" +
            " redactBackstopScrubs=${redactBackstopScrubs.get()}" +
            " unknownCustomerScrubs=${unknownCustomerScrubs.get()}" +
            " notifRedactBackstopScrubs=${notifRedactBackstopScrubs.get()}" +
            " notifListenerConnects=${notifListenerConnects.get()}" +
            " notifListenerDisconnects=${notifListenerDisconnects.get()}" +
            " restarts=${restarts.get()}"

    companion object {
        /** Forwarded-observation interval between periodic summary log lines. */
        const val SUMMARY_EVERY = 50L
    }
}
