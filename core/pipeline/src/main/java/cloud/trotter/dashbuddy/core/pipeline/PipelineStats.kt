package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.pipeline.ParseShortfall
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
     * `ruleId → admitted frames on which that rule MATCHED but its declared parse yielded
     * nothing usable` (#1036).
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
    private val parseShortfallByRule = ConcurrentHashMap<String, AtomicLong>()

    /** Rule ids already WARNed about this process — the once-per-rule edge gate (#1036). */
    private val parseShortfallWarned = ConcurrentHashMap.newKeySet<String>()

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
     * A rule MATCHED an admitted frame while its declared parse yielded nothing usable (#1036) —
     * the anchor-rot signature. Returns this rule's running count for the process.
     *
     * **Two grains, deliberately different.** The counter takes EVERY occurrence, so the periodic
     * summary carries a census; the WARN fires **once per rule per process** (the #937/#938 edge
     * gate), because a broken anchor fires on every frame of the surface and a per-frame WARN
     * would drown the level that means "a defended invariant fired" (principle 7). The per-rule
     * map and the per-rule gate live together so the two can't disagree about what "this rule"
     * means.
     *
     * **Callers must be post-admission.** Counting at classify time would let a dasher parked on
     * a rotted screen inflate the census with debounced duplicates, and a disabled platform accrue
     * counts at all (review R3) — so both pipelines call this after `FrameGate.admit`.
     *
     * PII-free: a rule id, a count, and — for the partial case — the names of fields WE declared.
     * No frame text of any kind, so the shareable INFO+ export is safe by construction.
     */
    fun onParseShortfall(shortfall: ParseShortfall): Long {
        val count = parseShortfallByRule.computeIfAbsent(shortfall.ruleId) { AtomicLong() }
            .incrementAndGet()
        if (parseShortfallWarned.add(shortfall.ruleId)) {
            Timber.tag(PARSE_HEALTH_TAG).w(
                "Rule %s matched with a parse shortfall — %s — anchor rot? (#1036)",
                shortfall.ruleId,
                describe(shortfall),
            )
        }
        return count
    }

    /** This rule's running parse-shortfall count for the process (#1036); 0 if it never tripped. */
    fun parseShortfallCount(ruleId: String): Long = parseShortfallByRule[ruleId]?.get() ?: 0L

    /**
     * The human half of the #1036 WARN: which of the two triggers fired, in the vocabulary the
     * rule author uses. Says `extractable` rather than `declared` because constants are excluded
     * from the count (review R7), and agrees with itself at n=1.
     */
    private fun describe(shortfall: ParseShortfall): String {
        val parts = mutableListOf<String>()
        if (shortfall.allNullFieldCount > 0) {
            val n = shortfall.allNullFieldCount
            parts += "all $n extractable field${if (n == 1) "" else "s"} unresolved"
        }
        if (shortfall.nullRequiredFields.isNotEmpty()) {
            val names = shortfall.nullRequiredFields.joinToString(", ")
            parts += "required field${if (shortfall.nullRequiredFields.size == 1) "" else "s"} null: $names"
        }
        return parts.joinToString("; ")
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
            " restarts=${restarts.get()}" +
            platformAppVersionsSuffix() +
            parseShortfallSuffix()

    /**
     * `" platformApps=com.doordash.driverapp@15.2.3,com.ubercab.driver@4.5"`, or empty.
     *
     * The emptiness check comes FIRST: building the string and then discarding it left a window
     * where an entry added between the two emitted a bare `" platformApps="` (review R8, the
     * shape #1036's suffix was copied from).
     */
    private fun platformAppVersionsSuffix(): String {
        if (platformAppVersions.isEmpty()) return ""
        return platformAppVersions.entries
            .sortedBy { it.key }
            .joinToString(separator = ",", prefix = " platformApps=") { "${it.key}@${it.value}" }
    }

    /**
     * `" parseShortfall{doordash.screen.delivery_summary_expanded=12,…}"`, or empty (#1036).
     *
     * Rule ids and counts only — the shareable INFO stream's PII rule holds by construction
     * (principle 7).
     *
     * **Bounded** (review R4): this line is written every [SUMMARY_EVERY] observations for the
     * whole process, and rules whose one extractable field is legitimately optional trip from the
     * first minutes of a dash — so an uncapped render would put a growing list on every summary
     * line in the exported bug report. The top [PARSE_SHORTFALL_RENDER_LIMIT] by count are shown
     * (that is the ordering that matters: rot is the loud one), each id clamped to
     * [MAX_RENDERED_RULE_ID] chars, with a `+k more` tail so the omission is stated rather than
     * silent. Ties break on the id so the render is deterministic.
     */
    private fun parseShortfallSuffix(): String {
        if (parseShortfallByRule.isEmpty()) return ""
        val entries = parseShortfallByRule.entries
            .map { it.key to it.value.get() }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
        val shown = entries.take(PARSE_SHORTFALL_RENDER_LIMIT)
        val omitted = entries.size - shown.size
        val body = shown.joinToString(",") { (id, n) -> "${clampRuleId(id)}=$n" }
        val tail = if (omitted > 0) ",+$omitted more" else ""
        return " parseShortfall{$body$tail}"
    }

    /** Keep one pathological rule id from owning the summary line (#1036 review R4). */
    private fun clampRuleId(id: String): String =
        if (id.length <= MAX_RENDERED_RULE_ID) id else id.take(MAX_RENDERED_RULE_ID) + "…"

    companion object {
        /** Forwarded-observation interval between periodic summary log lines. */
        const val SUMMARY_EVERY = 50L

        /** Timber tag for the #1036 parse-health WARN — its own component, not the classifier's. */
        const val PARSE_HEALTH_TAG = "ParseHealth"

        /** How many tripped rules the summary line renders before `+k more` (#1036 review R4). */
        const val PARSE_SHORTFALL_RENDER_LIMIT = 8

        /** Longest rule id rendered on the summary line before it is elided (#1036 review R4). */
        const val MAX_RENDERED_RULE_ID = 64
    }
}
