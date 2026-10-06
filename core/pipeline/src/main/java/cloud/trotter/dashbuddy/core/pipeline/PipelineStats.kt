package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.pipeline.ParseShortfall
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import timber.log.Timber
import cloud.trotter.dashbuddy.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.OverlayRejectReason
import cloud.trotter.dashbuddy.core.pipeline.census.NotificationSkeletonBuilder
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
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
class PipelineStats @Inject constructor(
    /**
     * OUR build's identity — `BuildConfig.VERSION_NAME`, which since PR #1066 carries the git sha
     * (`0.230.0+ab12cd34[.dirty]`). It rides the periodic summary next to the #937
     * `platformApps=` stamp so a field-data pull can read WHICH build produced the frames
     * straight off any log line, instead of inferring it from which lines are absent.
     *
     * Reaches `:core:pipeline` as a plain `String` through the SAME `@Named("appVersionName")`
     * seam `ReplayMetadataProviderImpl` already uses — one owner of the version string
     * (`AppModule.provideAppVersionName`, principle 5), and no `:core:pipeline` → `:app`
     * BuildConfig edge.
     */
    @param:Named("appVersionName") private val appVersionName: String,
    private val censusUploads: CensusUploadStats = CensusUploadStats(),
) {

    /**
     * Identity-less construction for the many unit tests that only exercise counters (PR #1066).
     *
     * A secondary constructor rather than a default argument: Kotlin synthesizes a no-arg
     * constructor when every primary parameter has a default AND copies the annotations onto it,
     * which Dagger rejects as a second `@Inject` constructor. An explicit un-annotated overload
     * leaves exactly one injection point.
     */
    constructor() : this(appVersionName = "")

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

    val censusSpooled: Long get() = censusUploads.spooled.get()
    val censusSpoolDropped: Long get() = censusUploads.spoolDropped.get()
    val censusUploaded: Long get() = censusUploads.uploaded.get()
    val censusDuplicate: Long get() = censusUploads.duplicate.get()
    val censusRejected: Map<String, Long> get() = censusUploads.rejectedCounts()
    val censusUploadFailures: Long get() = censusUploads.uploadFailures.get()

    /** #1146: census items and token decisions, counts only; disabled sinks leave these untouched. */
    private val censusEnvelopesPaired = AtomicLong()
    private val censusEnvelopesUnpaired = AtomicLong()
    private val censusSkeletons = AtomicLong()
    private val censusNotifications = AtomicLong()
    private val censusNotificationRefusals: Map<NotificationSkeletonBuilder.Refusal, AtomicLong> =
        EnumMap<NotificationSkeletonBuilder.Refusal, AtomicLong>(NotificationSkeletonBuilder.Refusal::class.java).apply {
            NotificationSkeletonBuilder.Refusal.entries.forEach { put(it, AtomicLong()) }
        }
    private val censusTokensHashed = AtomicLong()
    private val censusTokensWithheld = AtomicLong()
    private val censusSinkRefused = AtomicLong()
    private val censusPublishFailures = AtomicLong()
    /** #1171 review: an admitted UNKNOWN frame whose package resolves to no platform — refused BEFORE a build (ADR-0011 scopes the census to platform screens). */
    private val censusUnattributedPlatform = AtomicLong()

    /** #1146: builder refusals in declaration order, with no frame content. */
    private val censusRefusals: Map<SkeletonBuilder.Refusal, AtomicLong> =
        EnumMap<SkeletonBuilder.Refusal, AtomicLong>(SkeletonBuilder.Refusal::class.java).apply {
            SkeletonBuilder.Refusal.entries.forEach { put(it, AtomicLong()) }
        }

    /** #1152: frames read from a platform offer overlay (a11y `TYPE_SYSTEM`), on any window path. */
    private val overlaySnapshots = AtomicLong()

    /** PR #1155 review CC9: event-path enumerations looking for an enabled overlay above the active window. */
    private val overlayScans = AtomicLong()

    /** #1152 D2: system-layer windows refused as overlay candidates, per first failed check. */
    private val overlayRejections: Map<OverlayRejectReason, AtomicLong> =
        EnumMap<OverlayRejectReason, AtomicLong>(OverlayRejectReason::class.java).apply {
            OverlayRejectReason.entries.forEach { put(it, AtomicLong()) }
        }

    /**
     * #1148 review H3: EVENT-driven window-resolver skips, by reason (counts only) — its contract is
     * content/state frame loss. The topology path has its own census, [topologySkips].
     */
    private val foregroundSkips: Map<ForegroundSkipReason, AtomicLong> =
        EnumMap<ForegroundSkipReason, AtomicLong>(ForegroundSkipReason::class.java).apply {
            ForegroundSkipReason.entries.forEach { put(it, AtomicLong()) }
        }

    /** PR #1155 review FF4: TOPOLOGY-path bursts that emitted nothing, by reason (counts only). */
    private val topologySkips: Map<ForegroundSkipReason, AtomicLong> =
        EnumMap<ForegroundSkipReason, AtomicLong>(ForegroundSkipReason::class.java).apply {
            ForegroundSkipReason.entries.forEach { put(it, AtomicLong()) }
        }

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

    /** #1093 — optional `bind` targets a matched rule failed to resolve, keyed (ruleId, bind) —
     *  STRUCTURALLY, since a dotted string would merge `(a.b, c)` with `(a, b.c)`. */
    private val bindShortfallByKey = ConcurrentHashMap<Pair<String, String>, AtomicLong>()

    /** Keys that already WARNed this process (one WARN per rule+bind, like the parse WARN). */
    private val bindShortfallWarned = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    /** #1149 review R7 — action-target binds whose bind-time fingerprint was unprovable, keyed (ruleId, bind). */
    private val bindUnprovableByKey = ConcurrentHashMap<Pair<String, String>, AtomicLong>()
    private val bindUnprovableWarned = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    /** #1149 review S4 — action-target binds that resolved a node but were REFUSED (no clickable owner in the package). */
    private val bindRefusedByKey = ConcurrentHashMap<Pair<String, String>, AtomicLong>()
    private val bindRefusedWarned = ConcurrentHashMap.newKeySet<Pair<String, String>>()

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

    /**
     * A capture dropped whole by the fail-closed dasher-banking text-marker backstop (#432): an
     * UNKNOWN screen/click, the window title, or — since #1147 retired the "recognized buttons
     * carry no PII" premise — a RECOGNIZED click whose new string fields carry a marker. The
     * summary key `unknownScrubbed` is the retained legacy metric name for this counter.
     */
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
        // #1093 — the bind half rides the same shortfall but is its OWN census + WARN, keyed by
        // rule AND bind name: it must never move the #1036 parse count (a rule whose parse is
        // healthy and whose optional bind died is a different rot), and a rule with two optional
        // binds must say which one. Bind names are ours; no node content (P7).
        for (bind in shortfall.unresolvedOptionalBindings) {
            val key = shortfall.ruleId to bind
            bindShortfallByKey.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
            if (bindShortfallWarned.add(key)) {
                Timber.tag(PARSE_HEALTH_TAG).w(
                    "Rule %s matched but its optional bind '%s' resolved no node — target anchor rot? (#1093)",
                    shortfall.ruleId, bind,
                )
            }
        }
        for (bind in shortfall.unprovableBindings) {
            onBindUnprovable(shortfall.ruleId, bind, shortfall.unprovableReasons[bind] ?: "unprovable")
        }
        for (bind in shortfall.refusedBindings) onBindRefused(shortfall.ruleId, bind)
        if (!shortfall.hasParseTrigger) return parseShortfallCount(shortfall.ruleId)
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
     * #1149 review R7 — a matched rule bound an ACTION target whose bind-time label fingerprint is
     * unprovable (unreadable children, more than MAX_LABEL_HINTS labels): the tap can never use the
     * label re-find (2b) — or has no letter-bearing label at all (S7). Census on the summary
     * (`bindUnprovable{…}`), ONE WARN per rule+bind per process under [PARSE_HEALTH_TAG] (S6). Rule id,
     * bind name and our own reason wording only — PII-free.
     */
    fun onBindUnprovable(ruleId: String, bind: String, reason: String = "unprovable"): Long {
        val key = ruleId to bind
        val n = bindUnprovableByKey.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        if (bindUnprovableWarned.add(key)) {
            // S6: the shortfall family's one tag.
            Timber.tag(PARSE_HEALTH_TAG).w(
                "Rule %s bound '%s' with an unprovable label fingerprint (%s) — no label re-find for this target (#1149)",
                ruleId, bind, reason,
            )
        }
        return n
    }

    /**
     * #1149 review S4 — a matched rule's ACTION-target bind resolved a node that has no clickable owner
     * in the package (or is foreign): the target is WITHHELD (no reference). Its own census
     * (`bindRefused{…}`) and a truthful WARN — never the "optional bind resolved no node" line.
     */
    fun onBindRefused(ruleId: String, bind: String): Long {
        val key = ruleId to bind
        val n = bindRefusedByKey.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        if (bindRefusedWarned.add(key)) {
            Timber.tag(PARSE_HEALTH_TAG).w(
                "Rule %s: bind '%s' resolved a node with no clickable owner in the package — target withheld (#1149)",
                ruleId, bind,
            )
        }
        return n
    }

    fun bindRefusedCount(ruleId: String, bind: String): Long = bindRefusedByKey[ruleId to bind]?.get() ?: 0L

    fun bindUnprovableCount(ruleId: String, bind: String): Long = bindUnprovableByKey[ruleId to bind]?.get() ?: 0L

    /** This rule+bind's running unresolved-optional-bind count (#1093); 0 if it never tripped. */
    fun bindShortfallCount(ruleId: String, bind: String): Long =
        bindShortfallByKey[ruleId to bind]?.get() ?: 0L

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

    /**
     * The content/state window resolver produced no frame (#1148 review H3) — counted so the
     * enabled-package pre-map gate (a disabled platform's active frame now dies there, before
     * [onDisabledPlatformDrop] could see it) stays visible in the summary.
     */
    fun onForegroundSkip(reason: ForegroundSkipReason) {
        foregroundSkips.getValue(reason).incrementAndGet()
    }

    fun foregroundSkipCount(reason: ForegroundSkipReason): Long = foregroundSkips.getValue(reason).get()

    /** A topology burst emitted nothing (FF4) — `topologySkip{…}`, never mixed into `foregroundSkip{}`. */
    fun onTopologySkip(reason: ForegroundSkipReason) {
        topologySkips.getValue(reason).incrementAndGet()
    }

    fun topologySkipCount(reason: ForegroundSkipReason): Long = topologySkips.getValue(reason).get()

    /**
     * A frame was read from a platform offer overlay (#1152 D4–D6), counted in ONE place — the shared
     * `AccessibilitySource.getWindowSnapshot` builder (PR #1155 review FF6), on every window path. Rendered
     * as `overlaySnapshots=n` so a field pull can see overlays being captured at all.
     */
    fun onOverlaySnapshot() {
        overlaySnapshots.incrementAndGet()
    }

    fun overlaySnapshotCount(): Long = overlaySnapshots.get()

    /** One event-path `getWindows()` spent looking for an overlay above the active window (CC9). */
    fun onOverlayScan() {
        overlayScans.incrementAndGet()
    }

    fun overlayScanCount(): Long = overlayScans.get()

    /** A system-layer window was refused as an overlay candidate (#1152 D2) — `overlayRejected{…}`. */
    fun onOverlayRejected(reason: OverlayRejectReason) {
        overlayRejections.getValue(reason).incrementAndGet()
    }

    fun overlayRejectedCount(reason: OverlayRejectReason): Long = overlayRejections.getValue(reason).get()

    /** One built census skeleton and its hashed/withheld token counts (#1146). */
    fun onCensusSkeleton(hashed: Int, withheld: Int) {
        censusSkeletons.incrementAndGet()
        censusTokensHashed.addAndGet(hashed.toLong())
        censusTokensWithheld.addAndGet(withheld.toLong())
    }

    fun onCensusNotificationSkeleton(hashed: Int, withheld: Int) {
        onCensusSkeleton(hashed, withheld)
        censusNotifications.incrementAndGet()
    }

    fun onCensusNotificationRefused(reason: NotificationSkeletonBuilder.Refusal) {
        censusNotificationRefusals.getValue(reason).incrementAndGet()
    }

    fun censusNotificationCount(): Long = censusNotifications.get()
    fun censusNotificationRefusedCount(reason: NotificationSkeletonBuilder.Refusal): Long =
        censusNotificationRefusals.getValue(reason).get()

    /** The builder refused a census item; the admitted frame is unaffected (#1146). */
    fun onCensusRefused(reason: SkeletonBuilder.Refusal) {
        censusRefusals.getValue(reason).incrementAndGet()
    }

    /** #1200: the same frame's held UNKNOWN envelope was paired with its skeleton fingerprint — queued, or refused by the envelope sink. */
    fun onCensusEnvelopePaired(queued: Boolean) {
        if (queued) censusEnvelopesPaired.incrementAndGet() else censusEnvelopesUnpaired.incrementAndGet()
    }

    /** A built item was offered but not accepted by the census sink (#1146). */
    fun onCensusSinkRefused() {
        censusSinkRefused.incrementAndGet()
    }

    /** A census publisher failure was contained without losing a frame (#1146). */
    fun onCensusPublishFailure() {
        censusPublishFailures.incrementAndGet()
    }

    fun onCensusUnattributedPlatform() {
        censusUnattributedPlatform.incrementAndGet()
    }

    fun censusUnattributedPlatformCount(): Long = censusUnattributedPlatform.get()

    fun censusSkeletonCount(): Long = censusSkeletons.get()
    fun censusTokensHashedCount(): Long = censusTokensHashed.get()
    fun censusTokensWithheldCount(): Long = censusTokensWithheld.get()
    fun censusRefusedCount(reason: SkeletonBuilder.Refusal): Long = censusRefusals.getValue(reason).get()
    fun censusSinkRefusedCount(): Long = censusSinkRefused.get()
    fun censusPublishFailureCount(): Long = censusPublishFailures.get()

    /** An observation was forwarded to the state machine. */
    fun onForwarded() {
        val n = forwarded.incrementAndGet()
        if (n % SUMMARY_EVERY == 0L) logSummary("periodic")
    }

    fun logSummary(reason: String) {
        Timber.i("PipelineStats[%s]: %s", reason, summary())
    }

    fun summary(): String =
        appVersionSuffix() +
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
            " overlaySnapshots=${overlaySnapshots.get()}" +
            " overlayScans=${overlayScans.get()}" +
            platformAppVersionsSuffix() +
            parseShortfallSuffix() +
            bindShortfallSuffix() +
            bindUnprovableSuffix() +
            bindRefusedSuffix() +
            foregroundSkipSuffix() +
            topologySkipSuffix() +
            overlayRejectedSuffix() +
            censusSuffix()

    /** Census counts only (#1146); absent when untouched, preserving the existing summary bytes. */
    private fun censusSuffix(): String {
        val skeletons = censusSkeletons.get()
        val hashed = censusTokensHashed.get()
        val withheld = censusTokensWithheld.get()
        val sinkRefused = censusSinkRefused.get()
        val failures = censusPublishFailures.get()
        val unattributed = censusUnattributedPlatform.get()
        val envelopes = if (censusEnvelopesPaired.get() == 0L && censusEnvelopesUnpaired.get() == 0L) "" else
            ",envelopesPaired=${censusEnvelopesPaired.get()},envelopesUnpaired=${censusEnvelopesUnpaired.get()}"
        val uploads = censusUploads.summary()
        val refused = reasonSuffix(",refused", censusRefusals)
        val notifications = censusNotifications.get().let { if (it == 0L) "" else ",notifications=$it" }
        val notificationRefused = reasonSuffix(",notificationRefused", censusNotificationRefusals)
        if (skeletons == 0L && hashed == 0L && withheld == 0L && sinkRefused == 0L &&
            failures == 0L && unattributed == 0L && refused.isEmpty() && uploads.isEmpty() &&
            envelopes.isEmpty() && notifications.isEmpty() && notificationRefused.isEmpty()
        ) return ""
        return " census{skeletons=$skeletons,hashed=$hashed,withheld=$withheld," +
            "sinkRefused=$sinkRefused,failures=$failures,unattributed=$unattributed$refused$envelopes$uploads$notifications$notificationRefused}"
    }

    /**
     * The ONE renderer of a per-reason counter map (#1171 review: four hand-rolled copies had grown):
     * `"<prefix>{REASON=n,…}"` over the NON-ZERO reasons in enum declaration order, or the empty string
     * when none — so a suffix never appears while its counters are untouched.
     */
    private fun <E : Enum<E>> reasonSuffix(prefix: String, byReason: Map<E, AtomicLong>): String {
        val nonZero = byReason.entries.filter { it.value.get() > 0 }
        if (nonZero.isEmpty()) return ""
        return nonZero.joinToString(",", prefix = "$prefix{", postfix = "}") { "${it.key.name}=${it.value.get()}" }
    }

    /**
     * `" foregroundSkip{NO_ACTIVE_ROOT=3,FRONT_NOT_ENABLED=12}"` (#1148 review H3), non-zero reasons
     * only, in declaration order; empty when none. Enum names and counts — PII-free (principle 7).
     */
    private fun foregroundSkipSuffix(): String = reasonSuffix(" foregroundSkip", foregroundSkips)

    /** `" topologySkip{FRONT_NOT_ENABLED=3}"` (PR #1155 review FF4), non-zero reasons only; empty when none. */
    private fun topologySkipSuffix(): String = reasonSuffix(" topologySkip", topologySkips)

    /** `" overlayRejected{TOO_SMALL=40,NOT_OVERLAY_PLATFORM=2}"` (#1152), non-zero reasons only; empty when none. */
    private fun overlayRejectedSuffix(): String = reasonSuffix(" overlayRejected", overlayRejections)

    /**
     * `"app=0.230.0+ab12cd34 "`, or empty when no version was injected (PR #1066).
     *
     * Rendered FIRST — a field-data reader greps one summary line to answer "which build was
     * this?", so it must not be buried behind fourteen counters. The emptiness check comes first
     * for the same reason as [platformAppVersionsSuffix]: a bare `app=` would be worse than
     * silence. PII-free — our own version string (principle 7).
     */
    private fun appVersionSuffix(): String =
        if (appVersionName.isEmpty()) "" else "app=$appVersionName "

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
    private fun parseShortfallSuffix(): String = shortfallSuffix("parseShortfall", parseShortfallByRule) { it }

    /** `" bindShortfall{doordash.screen.delivery_summary_collapsed#expandButton=12,…}"` (#1093) — same
     *  bound, same clamp, same ordering as the parse suffix; rendered `<ruleId>#<bind>` (`#` cannot
     *  occur in either name, so the render is unambiguous). */
    private fun bindShortfallSuffix(): String =
        shortfallSuffix("bindShortfall", bindShortfallByKey) { (rule, bind) -> "${escapeKey(rule)}#${escapeKey(bind)}" }

    /** `" bindRefused{<rule>#<bind>=n,…}"` (#1149 review S4) — same bound, clamp and ordering. */
    private fun bindRefusedSuffix(): String =
        shortfallSuffix("bindRefused", bindRefusedByKey) { (rule, bind) -> "${escapeKey(rule)}#${escapeKey(bind)}" }

    /** `" bindUnprovable{<rule>#<bind>=n,…}"` (#1149 review R7) — same bound, clamp and ordering. */
    private fun bindUnprovableSuffix(): String =
        shortfallSuffix("bindUnprovable", bindUnprovableByKey) { (rule, bind) -> "${escapeKey(rule)}#${escapeKey(bind)}" }

    /** `#` is the pair separator in the render; a `#` INSIDE a component is escaped so two distinct
     *  pairs can never read as one (review round 3 — rule ids and bind names are not validated
     *  against it). */
    private fun escapeKey(component: String): String = component.replace("%", "%25").replace("#", "%23")

    /** Entries stay keyed by K through sorting and truncation; [render] is display only. */
    private fun <K> shortfallSuffix(label: String, byKey: Map<K, AtomicLong>, render: (K) -> String): String {
        if (byKey.isEmpty()) return ""
        val entries = byKey.entries
            .map { render(it.key) to it.value.get() }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
        val shown = entries.take(PARSE_SHORTFALL_RENDER_LIMIT)
        val omitted = entries.size - shown.size
        val body = shown.joinToString(",") { (id, n) -> "${clampRuleId(id)}=$n" }
        val tail = if (omitted > 0) ",+$omitted more" else ""
        return " $label{$body$tail}"
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
