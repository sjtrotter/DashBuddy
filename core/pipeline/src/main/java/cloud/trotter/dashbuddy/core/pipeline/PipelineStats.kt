package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.state.ParsedFields
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * `packageName → versionName` for every observed third-party app whose version resolved
     * this process (#937). A render-only record for [summary] — the resolution + its cache are
     * owned by [CachingPlatformAppVersions], which is the SSOT; this map only remembers what
     * that resolver already answered, so the two cannot disagree.
     */
    private val platformAppVersions = ConcurrentHashMap<String, String>()

    /**
     * `ruleId → frames on which that rule MATCHED but its declared parse yielded all-null` (#1036).
     *
     * The counter that makes anchor rot countable: DoorDash 8.93.7 removed the view ids every money
     * parse anchored on, the text-anchored `require` blocks kept matching, and every parse died —
     * silently, for weeks, because "matched and parsed nothing" and "matched, nothing to parse"
     * looked identical in every counter and log line we had.
     *
     * Keyed by rule id ONLY (principle 8) — no platform key, no per-platform threshold. Bounded by
     * the compiled ruleset's own rule count, and PII-free by construction: a rule id is our own
     * authored identifier, and no frame text is recorded.
     */
    private val parseAllNullByRule = ConcurrentHashMap<String, AtomicLong>()

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

    /**
     * An observed third-party app's `versionName` resolved for the first time this process
     * (#937). Recorded so the periodic summary — the INFO line a user can export as a bug
     * report — says WHICH build of the platform app produced the frames it is describing.
     *
     * Principle 7: a package name + a version string are platform-app facts, not user data.
     */
    fun onPlatformAppVersion(packageName: String, versionName: String) {
        platformAppVersions[packageName] = versionName
    }

    /**
     * A rule MATCHED a frame while every field its parse block declares resolved null (#1036) —
     * the anchor-rot signature. Returns this rule's running count for the process.
     *
     * Counting only, at every occurrence: the classifier owns the once-per-rule WARN edge, so the
     * counter stays a full census the periodic summary can render.
     */
    fun onParseAllNull(ruleId: String): Long =
        parseAllNullByRule.computeIfAbsent(ruleId) { AtomicLong() }.incrementAndGet()

    /** This rule's running all-null-parse count for the process (#1036); 0 if it never tripped. */
    fun parseAllNullCount(ruleId: String): Long = parseAllNullByRule[ruleId]?.get() ?: 0L

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
            " restarts=${restarts.get()}" +
            platformAppVersionsSuffix() +
            parseAllNullSuffix()

    /** `" platformApps=com.doordash.driverapp@15.2.3,com.ubercab.driver@4.5"`, or empty. */
    private fun platformAppVersionsSuffix(): String =
        platformAppVersions.entries
            .sortedBy { it.key }
            .joinToString(separator = ",", prefix = " platformApps=") { "${it.key}@${it.value}" }
            .takeIf { platformAppVersions.isNotEmpty() }
            ?: ""

    /**
     * `" parseAllNull{doordash.screen.delivery_summary_expanded=12,…}"`, or empty (#1036).
     *
     * Rule ids and counts only — the shareable INFO stream's PII rule holds by construction
     * (principle 7). Absent entirely while nothing has tripped, so a healthy summary line is
     * byte-identical to a pre-#1036 one.
     */
    private fun parseAllNullSuffix(): String =
        parseAllNullByRule.entries
            .sortedBy { it.key }
            .joinToString(separator = ",", prefix = " parseAllNull{", postfix = "}") {
                "${it.key}=${it.value.get()}"
            }
            .takeIf { parseAllNullByRule.isNotEmpty() }
            ?: ""

    companion object {
        /** Forwarded-observation interval between periodic summary log lines. */
        const val SUMMARY_EVERY = 50L
    }
}
