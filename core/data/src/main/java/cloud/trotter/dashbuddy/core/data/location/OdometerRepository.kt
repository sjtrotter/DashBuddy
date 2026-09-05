package cloud.trotter.dashbuddy.core.data.location

import cloud.trotter.dashbuddy.core.datastore.odometer.OdometerLocalDataSource
import cloud.trotter.dashbuddy.core.location.LocationDataSource
import cloud.trotter.dashbuddy.domain.location.OdometerFixPolicy
import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import java.util.Locale

@Singleton
class OdometerRepository @Inject constructor(
    private val odometerLocalDataSource: OdometerLocalDataSource,
    private val locationDataSource: LocationDataSource,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var trackingJob: Job? = null

    // State
    /**
     * The cumulative device odometer total (meters). THE `metadata.odometer` input the analytics
     * projector reads (#314) — a raw cumulative reading. #438 B5 does NOT change its semantics; only
     * the per-session anchor bookkeeping below changed.
     */
    private val _meters = MutableStateFlow(0.0)

    /**
     * #438 B5: per-session start anchors (sessionId → odometer meters at session start). Replaces the
     * single global `_anchor`, so two concurrent sessions accrue miles independently and a second
     * session starting can't zero the first's total.
     */
    private val _anchors = MutableStateFlow<Map<String, Double>>(emptyMap())

    /** The session the no-arg [sessionMilesFlow] tracks (the HUD's "session miles" view). */
    private val _currentSessionId = MutableStateFlow<String?>(null)

    // Public Output: Reactive Session Miles for the CURRENT session (the HUD's "session miles").
    val sessionMilesFlow: Flow<Double> =
        combine(_meters, _anchors, _currentSessionId) { total, anchors, sid ->
            val anchor = sid?.let { anchors[it] } ?: 0.0
            (total - anchor).coerceAtLeast(0.0) * metersToMiles
        }

    /**
     * The last fix the gate ACCEPTED — the reference every displacement is measured from. #1057/#918:
     * an ignored (jitter) or rejected (implausible) fix deliberately leaves it where it is, so a
     * teleport anchors nothing and slow creep still accumulates against a fixed point.
     */
    private var lastAccepted: Coordinates? = null
    private val metersToMiles = 0.000621371

    // #1057 ask 3 — bounded observability. All four are CUMULATIVE for the life of the process, so
    // each summary line is a self-contained snapshot; ONE DEBUG line per SUMMARY_EVERY judged fixes,
    // never a per-fix line (the 1 Hz notification DEBUG already costs 13.8 % of the firehose, #1001).
    private var acceptedCount = 0L
    private var ignoredCount = 0L
    private var rejectedCount = 0L
    private var acceptedMeters = 0.0

    // #1057 round 2 — rejection logging is EPISODE-gated, not per-fix. Poor reception is a
    // CONDITION, not an event: a canyon or garage stretch at 60 m accuracy rejects every fix at the
    // provider's 2–5 s cadence, i.e. 720–1,800 WARNs an hour, which drowns the exceptional
    // speed/jump rejects a WARN exists to surface (principle 7: "if it's benign and frequent, it's
    // VERBOSE"). A streak of consecutive rejections is ONE episode: WARN on entry, WARN every
    // [STREAK_REMINDER_EVERY] while it runs, INFO once when reception recovers — and a rejection
    // whose reason DIFFERS from the one that opened the streak still WARNs individually, because a
    // different reason is a new fact rather than a repetition of the one already reported.
    private var streakReason: OdometerFixPolicy.Reason? = null
    private var streakLength = 0L
    private var streakStartedAtMs = 0L

    init {
        // Read persisted values ONCE at startup (#364): the old collectors let a
        // stale DataStore emission (from an in-flight save echo) transiently
        // REGRESS the live value mid-session. The live StateFlows are the
        // single source of truth after startup; saves flow one-way to disk.
        scope.launch {
            _meters.value = odometerLocalDataSource.totalMetersFlow.first()
            // #438 B5: restore the current session's anchor so HUD miles survive a crash.
            val currentId = odometerLocalDataSource.currentSessionIdFlow.first()
            if (currentId != null) {
                _currentSessionId.value = currentId
                val anchor = odometerLocalDataSource.sessionAnchorFlow(currentId).first()
                if (anchor != null) _anchors.value = mapOf(currentId to anchor)
            }
        }
    }

    /**
     * #438 B5: anchor [sessionId] to the current cumulative total the first time it starts, and mark
     * it the current session. **Idempotent** — a grace-resume that re-arms the session MUST NOT move
     * an existing anchor (that would zero the session's accrued miles, the exact concurrency bug this
     * replaces). Replaces the old global `resetSession()`.
     */
    fun startSessionTracking(sessionId: String) {
        _currentSessionId.value = sessionId
        if (_anchors.value.containsKey(sessionId)) return
        val anchor = _meters.value
        _anchors.value = _anchors.value + (sessionId to anchor)
        scope.launch { odometerLocalDataSource.saveSessionAnchor(sessionId, anchor) }
    }

    /**
     * #438 B5: per-session miles — session A and B accrue independently. A session with no anchor yet
     * reads 0 (anchor defaults to the current total). #528 consumes these + the overlap flag for
     * per-drop / GPS $/mi.
     */
    fun sessionMilesFlow(sessionId: String): Flow<Double> =
        combine(_meters, _anchors) { total, anchors ->
            val anchor = anchors[sessionId] ?: total
            (total - anchor).coerceAtLeast(0.0) * metersToMiles
        }

    fun startTracking() {
        if (trackingJob?.isActive == true) return
        Timber.tag(TAG).i("Starting GPS Tracking...")
        trackingJob = scope.launch {
            locationDataSource.locationUpdates.collect { processLocation(it) }
        }
    }

    fun stopTracking() {
        if (trackingJob?.isActive == true) {
            Timber.tag(TAG).i("Stopping GPS Tracking.")
            trackingJob?.cancel()
            trackingJob = null
            lastAccepted = null
            // An open rejection episode ends with the tracking run, not with a recovery — clear it
            // silently so the next run's first reject opens a fresh episode rather than logging a
            // "recovered" line for reception that never came back.
            streakReason = null
            streakLength = 0L
        }
    }

    /**
     * The odometer's only write path. Every fix is judged by the pure [OdometerFixPolicy] (#1057/#918)
     * — this method owns nothing but the side effects: accrual, the reference, and the log lines.
     */
    private fun processLocation(coords: Coordinates) {
        when (val verdict = OdometerFixPolicy.judge(lastAccepted, coords)) {
            is OdometerFixPolicy.Verdict.Reference -> {
                lastAccepted = coords
                closeRejectionStreak()
            }

            is OdometerFixPolicy.Verdict.Accept -> {
                lastAccepted = coords
                acceptedCount++
                acceptedMeters += verdict.deltaMeters
                addMeters(verdict.deltaMeters)
                closeRejectionStreak()
            }

            is OdometerFixPolicy.Verdict.Ignore -> {
                ignoredCount++
                // The reference's POSITION is deliberately unmoved (it is the accrual anchor, so
                // creep under the floor still accumulates against it) — but its TIMING advances to
                // this fix. #1057 round 2: leaving the timestamp stale let elapsed time grow without
                // bound while parked, so a 1,457 km teleport after seven stationary hours implied a
                // perfectly plausible 57.8 m/s and was ACCEPTED. Accuracy is left alone too — the
                // jitter floor belongs to the anchor, not to the latest bounce.
                lastAccepted = lastAccepted?.copy(
                    timestampMs = coords.timestampMs,
                    monotonicMs = coords.monotonicMs,
                )
                closeRejectionStreak()
            }

            is OdometerFixPolicy.Verdict.Reject -> {
                rejectedCount++ // reference deliberately unmoved — a bad fix anchors nothing
                logRejection(verdict)
            }
        }
        maybeLogSummary()
    }

    /**
     * WARN: a defended invariant fired (principle 7), but bounded to one episode rather than one
     * line per rejected fix. NUMBERS ONLY — a latitude/longitude is the dasher's location (PII) and
     * is never logged, at any level.
     */
    private fun logRejection(verdict: OdometerFixPolicy.Verdict.Reject) {
        val now = System.currentTimeMillis()
        val openingReason = streakReason
        streakLength++

        if (openingReason == null) {
            streakReason = verdict.reason
            streakStartedAtMs = now
            Timber.tag(TAG).w(rejectionLine(verdict) + " — entering a rejection streak")
            return
        }

        if (verdict.reason != openingReason) {
            // A different reason inside a streak is a new fact: an IMPLAUSIBLE_SPEED reject during a
            // poor-accuracy stretch is exactly the exceptional event the episode gate must not hide.
            Timber.tag(TAG).w(rejectionLine(verdict))
            return
        }

        if (streakLength % STREAK_REMINDER_EVERY == 0L) {
            Timber.tag(TAG).w(
                String.format(
                    Locale.ROOT,
                    "Odometer rejection streak: %d fixes over %d s, latest reason %s",
                    streakLength,
                    (now - streakStartedAtMs) / 1000L,
                    verdict.reason.name,
                )
            )
        }
    }

    /** ONE INFO on the first usable fix after a streak — the episode's closing bracket. */
    private fun closeRejectionStreak() {
        if (streakReason == null) return
        val seconds = (System.currentTimeMillis() - streakStartedAtMs) / 1000L
        Timber.tag(TAG).i(
            String.format(
                Locale.ROOT,
                "Odometer reception recovered after %d rejected fixes over %d s",
                streakLength,
                seconds,
            )
        )
        streakReason = null
        streakLength = 0L
    }

    private fun rejectionLine(verdict: OdometerFixPolicy.Verdict.Reject): String = String.format(
        Locale.ROOT,
        "Odometer fix rejected (%s): delta=%.0fm dt=%sms impliedSpeed=%s m/s accuracy=%s m",
        verdict.reason.name,
        verdict.deltaMeters,
        verdict.dtMillis?.toString() ?: "n/a",
        verdict.impliedMps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "n/a",
        verdict.accuracyMeters?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "n/a",
    )

    /** ONE bounded DEBUG line per [SUMMARY_EVERY] judged fixes — counters and meters only. */
    private fun maybeLogSummary() {
        val judged = acceptedCount + ignoredCount + rejectedCount
        if (judged == 0L || judged % SUMMARY_EVERY != 0L) return
        Timber.tag(TAG).d(
            String.format(
                Locale.ROOT,
                "Odometer fixes: accepted=%d ignored=%d rejected=%d, +%.0f m",
                acceptedCount,
                ignoredCount,
                rejectedCount,
                acceptedMeters,
            )
        )
    }

    private fun addMeters(delta: Double) {
        val newTotal = _meters.value + delta
        _meters.value = newTotal
        scope.launch { odometerLocalDataSource.saveTotalMeters(newTotal) }
    }

    /** Miles accrued by [sessionId] (its total delta since its anchor). */
    fun getCurrentSessionMiles(sessionId: String): Double {
        val anchor = _anchors.value[sessionId] ?: _meters.value
        return (_meters.value - anchor).coerceAtLeast(0.0) * metersToMiles
    }

    /**
     * The raw cumulative device odometer reading (miles) — the projector's `metadata.odometer` input
     * (#314). Unaffected by the #438 B5 per-session anchor change (it reads [_meters] only).
     */
    fun getCurrentMiles(): Double = _meters.value * metersToMiles

    private companion object {
        private const val TAG = "Odometer"

        /** How many judged fixes between DEBUG summary lines (#1057 ask 3, bounded). */
        private const val SUMMARY_EVERY = 100L

        /**
         * How many consecutive rejections between WARN reminders inside one episode (#1057 round 2).
         * At the fused provider's 2–5 s cadence that is a reminder every 3–8 minutes of continuous
         * poor reception, against the 720–1,800 lines an hour a per-fix WARN emitted.
         */
        private const val STREAK_REMINDER_EVERY = 100L
    }
}
