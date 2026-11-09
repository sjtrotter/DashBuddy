package cloud.trotter.log.gig.core.pipeline

import cloud.trotter.log.gig.domain.census.HealthSink
import cloud.trotter.log.gig.domain.di.ApplicationScope
import cloud.trotter.log.gig.domain.di.IoDispatcher
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ParseShortfall
import cloud.trotter.log.gig.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncidents
import cloud.trotter.log.gig.domain.pipeline.RecognitionNoticeStore
import cloud.trotter.log.gig.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one recognition incident owner per platform (#937, #1560 A2): one platform update posts one
 * notice, whether its screens stopped being recognized or its rules stopped reading them.
 *
 * The decisions are pure and live elsewhere: [RecognitionHealth] (the UNKNOWN rate), [ParseRotTracker]
 * (a rule that matches but reads nothing) and [RecognitionIncidentBook] (the incident per platform and
 * the notice cadence). This class owns the edges: the package→platform resolution, the lock, the open
 * incidents as a [StateFlow] for Home, the last-notice persistence, the log lines, and one ordered
 * coroutine on the IO dispatcher that persists and calls the reporter.
 *
 * **In memory, except one value.** Incidents live in memory: a restart starts clean. Only the last
 * notice time per platform wire persists ([RecognitionNoticeStore]), so the one-notice-a-day limit
 * survives a restart. It loads once at construction (bounded by [LOAD_TIMEOUT_MS]) and no notice is
 * taken before the load; after the load, a notice the previous process left in the shade for a platform
 * with no open incident is withdrawn. Before a notice posts its time is written (bounded by
 * [SAVE_TIMEOUT_MS]), so a force-stop between the two cannot buy a second notice; a failed or slow
 * write never stops the post.
 *
 * **Ordering.** Every reporter action is enqueued under the same lock as the decision that produced it,
 * and the startup closes are enqueued under the lock before notices are enabled; one consumer drains the
 * queue in order. So a close never overtakes a notice queued before it and never cancels one queued after.
 *
 * **The UNKNOWN cause is process-scoped.** It joins the platform's incident on [RecognitionHealth]'s
 * once-per-process trip and leaves only with the process. It does not recover by rate: past
 * `UnknownSuppressor`'s burst cap (200 distinct UNKNOWN frames, re-armed after a 30-minute gap) no
 * UNKNOWN frame reaches this monitor, so a rate would read a live break as healthy.
 *
 * **Fail-open, absolutely.** This is called from inside the sensing flow, one call per admitted
 * frame. Every path is wrapped in `catch (Throwable)` (only `CancellationException` rethrown —
 * the #909 rule), because a health diagnostic that can kill the pipeline would be the very
 * failure it exists to detect. The sensing call only updates memory and enqueues; it never writes to
 * disk and never calls the reporter. Nothing here changes what a frame classifies as or whether it is
 * forwarded, and a ruleset reload never clears an incident (only healthy parses do).
 *
 * **Platform-agnostic (principle 8).** Frames are keyed by `Platform.wire` via the registry;
 * there is no platform literal here and nothing is tuned to whichever platform we field-test
 * most. A frame whose package resolves to `Unknown` is ignored — an unattributable frame has no
 * recognition contract to be measured against.
 */
@Singleton
class RecognitionHealthMonitor @Inject constructor(
    private val reporter: RecognitionHealthReporter,
    private val sink: HealthSink,
    private val store: RecognitionNoticeStore,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
) : RecognitionIncidents {

    /** One reporter action, performed in enqueue order by the IO consumer. Events are rare: one per incident edge. */
    private sealed interface Action {
        data class Post(val incident: RecognitionIncident, val lastNoticeAt: Map<String, Long>) : Action
        data class Close(val platformWire: String) : Action
    }

    /** What one frame decided; logged outside the lock. */
    private class FrameOutcome(
        val alarm: RecognitionHealth.Alarm?,
        val edges: List<RotEdge>,
        /** The closed incident's `openedAt`, when this frame closed it. */
        val closedOpenedAt: Long?,
        val notice: RecognitionIncident?,
    )

    private val lock = Any()
    private val health = RecognitionHealth()
    private val rot = ParseRotTracker()
    private val book = RecognitionIncidentBook()
    private var loaded = false

    private val _open = MutableStateFlow<List<RecognitionIncident>>(emptyList())
    override val open: StateFlow<List<RecognitionIncident>> = _open.asStateFlow()

    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val saveWarned = AtomicBoolean(false)

    init {
        scope.launch(io) {
            val persisted: Map<String, Long> = try {
                withTimeoutOrNull(LOAD_TIMEOUT_MS) { store.load() }
                    ?: emptyMap<String, Long>().also { warnLoad("timeout") }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                warnLoad(t.javaClass.simpleName)
                emptyMap()
            }

            synchronized(lock) {
                book.seedLastNoticeAt(persisted)
                // A notice the previous process left in the shade, for a platform with no open incident.
                for (wire in persisted.keys) {
                    if (!book.hasIncident(wire)) actions.trySend(Action.Close(wire))
                }
                loaded = true
            }

            for (action in actions) perform(action)
        }
    }

    /**
     * Record one frame that survived `FrameGate` admission.
     *
     * @param packageName the window's real package (the frame's own attribution).
     * @param obs the admitted screen (any flow observation of a screen event), with its recognition and version stamps.
     */
    fun onScreenAdmitted(packageName: String?, obs: Observation.FlowObservation) {
        val unknown = obs.target == UNKNOWN_TARGET
        try {
            val platform = Platform.fromPackage(packageName)
            if (platform == Platform.Unknown) return
            val wire = platform.wire

            try {
                sink.onScreen(obs.timestamp, wire, obs.metadata.platformAppVersion, obs.ruleId?.takeUnless { unknown })
            } catch (t: Throwable) {
                Timber.tag(TAG).w("Health ledger record failed — sensing unaffected")
            }

            val shortfalls = (obs as? Observation.Screen)?.parseShortfalls.orEmpty()
            val frame = synchronized(lock) {
                decide(wire, obs.timestamp, unknown, obs.ruleId.takeUnless { unknown }, shortfalls)
            }

            frame.alarm?.let { alarm ->
                try {
                    sink.onTrip(obs.timestamp, wire, obs.metadata.platformAppVersion)
                } catch (t: Throwable) {
                    Timber.tag(TAG).w("Health ledger record failed — sensing unaffected")
                }

                // WARN, not ERROR: a defended invariant fired and the app is degraded, but nothing
                // is lost or crashed (principle 7). PII-free by construction — a platform wire and
                // a percentage, no screen text.
                Timber.tag(TAG).w(
                    "Recognition health: %d%% of the last %d %s frames classified UNKNOWN — " +
                        "anchors may be broken by a platform app update (#937)",
                    alarm.unknownPercent,
                    RecognitionHealth.WINDOW_SIZE,
                    alarm.platformWire,
                )
            }

            try {
                logFrame(wire, obs.timestamp, frame)
            } catch (t: Throwable) {
                Timber.tag(TAG).w("Recognition incident log failed — sensing unaffected (#1560)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Recognition health check failed — sensing unaffected (#937)")
        }
    }

    /** Called under [lock]. Applies the frame's edges to the book and enqueues what they decided. */
    private fun decide(
        wire: String,
        timestamp: Long,
        unknown: Boolean,
        winnerRuleId: String?,
        shortfalls: List<ParseShortfall>,
    ): FrameOutcome {
        val alarm = health.record(wire, unknown)
        val edges = rot.record(wire, timestamp, winnerRuleId, shortfalls)
        val openedBefore = if (edges.any { it is RotEdge.Cleared }) {
            book.open().firstOrNull { it.platformWire == wire }?.openedAt
        } else {
            null
        }

        // Trips first, then clears (L8), and only then closure.
        var changed = alarm?.let { book.unknownTripped(wire, timestamp) } ?: false
        for (edge in edges) {
            if (edge is RotEdge.Tripped) changed = book.rotTripped(wire, edge.cause) || changed
        }
        for (edge in edges) {
            if (edge is RotEdge.Cleared) changed = book.rotCleared(wire, edge.ruleId) || changed
        }
        val closed = book.closedSinceLastTake()

        val notice = if (loaded) book.takeDueNotice(wire, timestamp) else null
        if (changed || notice != null || closed.isNotEmpty()) _open.value = book.open()

        // Enqueued under the lock that decided them, in decision order (M2).
        closed.forEach { actions.trySend(Action.Close(it)) }
        notice?.let { actions.trySend(Action.Post(it, book.lastNoticeAt())) }

        return FrameOutcome(alarm, edges, openedBefore?.takeIf { closed.isNotEmpty() }, notice)
    }

    /** The #1560 log lines: our own identifiers, counts and minutes only (principle 7). */
    private fun logFrame(wire: String, timestamp: Long, frame: FrameOutcome) {
        for (edge in frame.edges) {
            when (edge) {
                is RotEdge.Tripped -> Timber.tag(TAG).w(
                    "Parse rot on %s: rule %s, %d visits with %s null — incident open (#1560)",
                    wire,
                    edge.cause.ruleId,
                    ParseRotTracker.TRIP_OCCURRENCES,
                    edge.cause.nullFields.joinToString(", "),
                )
                is RotEdge.Cleared -> Timber.tag(TAG).i(
                    "Parse rot cleared on %s: rule %s parsed %d times (#1560)",
                    wire,
                    edge.ruleId,
                    ParseRotTracker.CLEAR_HEALTHY,
                )
            }
        }
        frame.closedOpenedAt?.let { openedAt ->
            Timber.tag(TAG).i(
                "Recognition incident closed on %s after %d min (#1560)",
                wire,
                (timestamp - openedAt).coerceAtLeast(0L) / 60_000L,
            )
        }
        frame.notice?.let { incident ->
            val causes = (if (incident.screensUnrecognized) 1 else 0) + incident.parseRot.size
            Timber.tag(TAG).i("Recognition notice posted on %s: %d cause(s) (#1560)", wire, causes)
        }
    }

    /** IO consumer only: persist, then report. Each step fails open. */
    private suspend fun perform(action: Action) {
        when (action) {
            is Action.Post -> {
                // The write comes first so a force-stop between the two cannot buy a second notice after
                // a restart; a failed or slow write never stops the post.
                try {
                    withTimeoutOrNull(SAVE_TIMEOUT_MS) { store.save(action.lastNoticeAt) } ?: warnSave("timeout")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    warnSave(t.javaClass.simpleName)
                }
                report { reporter.onIncidentNotice(action.incident) }
            }
            is Action.Close -> report { reporter.onIncidentClosed(action.platformWire) }
        }
    }

    private inline fun report(call: () -> Unit) {
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Deliberately broad and NOT rethrown (#909): a notice the OS refuses costs a log line.
            Timber.tag(TAG).e(t, "Recognition notice could not be delivered (#1560)")
        }
    }

    private fun warnLoad(reason: String) {
        Timber.tag(TAG).w("Recognition notice store load failed (%s) — notices fail open (#1560)", reason)
    }

    private fun warnSave(reason: String) {
        if (saveWarned.compareAndSet(false, true)) {
            Timber.tag(TAG).w("Recognition notice store save failed (%s) — the notice posts anyway (#1560)", reason)
        }
    }

    internal companion object {
        private const val TAG = "RecognitionHealth"

        /** Bound on the startup load of the last notice times; a timeout fails open. */
        const val LOAD_TIMEOUT_MS = 10_000L

        /** Bound on writing a notice's time before it posts; a timeout posts anyway. */
        const val SAVE_TIMEOUT_MS = 2_000L
    }
}
