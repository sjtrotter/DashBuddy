package cloud.trotter.dashbuddy.state.effects

/**
 * When a spoken utterance is lost, decide what to do about it (#991).
 *
 * Pure Kotlin — no Android types, no wall clock of its own (`now` is always a parameter), so the
 * whole escalation ladder is a plain unit test. [TtsEffectHandler] owns the Android side (tearing
 * the engine down, rebuilding it, posting the notice); this class owns only the *when*.
 *
 * ### Why it exists
 * The 2026-08-09 desk pull found the offer voice dead for three whole dashes: the engine's service
 * binder had dropped (a Google TTS package update under a process that had been alive 8 days), so
 * every `speak()` returned `ERROR` and the handler — which built its engine once in `init` and set
 * `isReady` exactly once — had no path back. 15 of 15 utterances lost, with nothing louder than a
 * WARN in a log nobody reads mid-dash. This is the fifth member of the #909 silent-death family
 * (effect engine #914, bubble #916, odometer #917, recognition #937): a subsystem that dies quietly
 * inside a live process is worse than one that crashes.
 *
 * ### What counts as a failure
 * Every way an utterance can be lost, not just the obvious one — a non-`SUCCESS` `speak()` return,
 * an asynchronous `UtteranceProgressListener.onError`, an engine that fails to initialize, and an
 * offer skipped because a recovery in flight never produced a working engine. A `speak()` that
 * returns `SUCCESS` only means *queued*, so the reset lives on completion, not on that return.
 *
 * ### The ladder
 * - **First failure ⇒ rebuild, immediately.** A dead binder is the common case and rebuilding costs
 *   a fraction of a second; making the dasher wait 30 s to find out would be pointless caution.
 * - **Later failures are gated by a linear, capped backoff** — [BACKOFF_STEP_MILLIS] × the number
 *   of rebuilds already attempted, ceilinged at [MAX_BACKOFF_MILLIS] (30 s, 60 s, 90 s … 5 min).
 *   Linear rather than exponential because the realistic worst case is a package update that
 *   resolves in a minute or two, and a doubling schedule would be at hours by the end of a dash.
 *   Same shape as the #430 pipeline / #909 drain-worker supervision.
 * - **A sustained, demonstrably unrecovered outage ⇒ notify, once per process.** Three gates must
 *   all hold ([NOTIFY_AFTER_FAILURES] consecutive losses, [MIN_REBUILDS_BEFORE_NOTIFY] rebuilds
 *   already attempted, and [MIN_STREAK_MILLIS] of wall time since the streak began) because the
 *   notice cannot be un-fired: it is once per process by design, so burning it on a burst of
 *   offers arriving during a single slow-but-successful rebuild would leave a *real* death later
 *   in that process with no disclosure at all. Time is the honest discriminator — a rebuild that
 *   works finishes in well under a minute.
 *
 * ### Composition
 * Notifying and rebuilding are independent (#991 review finding 6): telling the dasher the voice
 * is gone is not a reason to skip the rebuild that might bring it back, so [Decision] carries both
 * flags and a single failure can set either, both, or neither.
 *
 * ### State
 * A completed utterance clears the streak, the backoff and the elapsed anchor — the next failure
 * starts over at "rebuild immediately". The notify latch is deliberately NOT cleared: one notice
 * per process, the same contract the #937/#938 app notices hold, so a flapping engine can't turn a
 * disclosure into a nag. A process restart re-arms everything.
 *
 * Synchronized because [TtsEffectHandler] is a `@Singleton` whose failures arrive from the effect
 * drain worker AND from the engine's own asynchronous callback thread.
 */
class TtsRecoveryPolicy {

    /**
     * What the handler should do about the failure just reported. The two flags are independent —
     * a failure can rebuild, notify, both, or (inside the backoff window, pre-notice) neither.
     */
    data class Decision(
        /** Tear the engine down and build a new one now. */
        val rebuild: Boolean,
        /** Tell the dasher the voice is gone. True at most once per process. */
        val notify: Boolean,
    ) {
        /** True when the failure was absorbed silently — inside the backoff, nothing else due. */
        val isWait: Boolean get() = !rebuild && !notify
    }

    private var consecutiveFailures = 0
    private var rebuildAttempts = 0
    private var lastRebuildAtMillis: Long? = null
    private var streakStartedAtMillis: Long? = null
    private var notified = false

    /** Consecutive lost utterances since the last completed one. Diagnostics/logging only. */
    val failureStreak: Int
        @Synchronized get() = consecutiveFailures

    /**
     * Record a lost utterance at [nowMillis] and decide what follows. Every call advances the
     * streak; only a rebuild arms the next backoff window.
     */
    @Synchronized
    fun onSpeechFailure(nowMillis: Long): Decision {
        consecutiveFailures++
        anchorStreak(nowMillis)
        // Order matters only for readability: the two arms read disjoint mutable state apart from
        // rebuildAttempts, which shouldNotify reads AFTER shouldRebuild has counted this attempt —
        // deliberately, so "a rebuild is happening right now" counts toward the notify gate.
        val rebuild = shouldRebuild(nowMillis)
        val notify = shouldNotify(nowMillis)
        return Decision(rebuild = rebuild, notify = notify)
    }

    /** An utterance finished speaking: the streak, the backoff and the elapsed anchor all reset. */
    @Synchronized
    fun onUtteranceCompleted() {
        consecutiveFailures = 0
        rebuildAttempts = 0
        lastRebuildAtMillis = null
        streakStartedAtMillis = null
    }

    /** The backoff window that must elapse before the *next* rebuild, given the attempts so far. */
    @Synchronized
    fun currentBackoffMillis(): Long =
        (BACKOFF_STEP_MILLIS * rebuildAttempts).coerceAtMost(MAX_BACKOFF_MILLIS)

    /**
     * Start (or re-anchor) the elapsed-time window the notify gate measures. A wall clock can step
     * backwards (NTP correction, manual set); re-anchoring forward-only keeps a backwards jump from
     * manufacturing a huge "elapsed" and firing the notice early.
     */
    private fun anchorStreak(nowMillis: Long) {
        val started = streakStartedAtMillis
        if (started == null || nowMillis < started) streakStartedAtMillis = nowMillis
    }

    private fun shouldRebuild(nowMillis: Long): Boolean {
        val last = lastRebuildAtMillis
        if (last != null && !elapsedAllowsRebuild(last, nowMillis)) return false
        lastRebuildAtMillis = nowMillis
        rebuildAttempts++
        return true
    }

    private fun shouldNotify(nowMillis: Long): Boolean {
        if (notified) return false
        if (consecutiveFailures < NOTIFY_AFTER_FAILURES) return false
        if (rebuildAttempts < MIN_REBUILDS_BEFORE_NOTIFY) return false
        val started = streakStartedAtMillis ?: return false
        if (nowMillis - started < MIN_STREAK_MILLIS) return false
        notified = true
        return true
    }

    /**
     * A backwards wall-clock step must re-anchor and allow the rebuild rather than freeze recovery
     * until the clock catches up — fail toward trying again.
     */
    private fun elapsedAllowsRebuild(lastMillis: Long, nowMillis: Long): Boolean {
        val elapsed = nowMillis - lastMillis
        return elapsed < 0L || elapsed >= currentBackoffMillis()
    }

    companion object {
        /** Backoff grows by this much per rebuild already attempted. */
        const val BACKOFF_STEP_MILLIS = 30_000L

        /** Ceiling on the backoff — never wait longer than this between rebuild attempts. */
        const val MAX_BACKOFF_MILLIS = 300_000L

        /** Consecutive lost utterances required before the dasher is told. */
        const val NOTIFY_AFTER_FAILURES = 3

        /**
         * Rebuilds that must already have been attempted before the notice fires. Two, not one:
         * one rebuild proves nothing (the failure that triggered it may have been the last of the
         * outage), while a second means the first rebuild was given a full backoff window and the
         * voice still did not come back.
         */
        const val MIN_REBUILDS_BEFORE_NOTIFY = 2

        /**
         * Wall time the failure streak must have lasted before the notice fires. A successful
         * rebuild completes in well under a minute, so a minute of continuous failure is the
         * cheapest honest evidence that this is an outage and not a burst of offers landing during
         * one slow recovery.
         */
        const val MIN_STREAK_MILLIS = 60_000L
    }
}
