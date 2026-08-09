package cloud.trotter.dashbuddy.state.effects

/**
 * When a `TextToSpeech.speak()` call fails, decide what to do about it (#991).
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
 * ### The ladder
 * - **First failure ⇒ [Decision.RETRY_REINIT], immediately.** A dead binder is the common case and
 *   rebuilding costs a fraction of a second; making the dasher wait 30 s to find out would be
 *   pointless caution.
 * - **Later failures are gated by a linear, capped backoff** — [BACKOFF_STEP_MILLIS] × the number
 *   of re-inits already attempted, ceilinged at [MAX_BACKOFF_MILLIS] (30 s, 60 s, 90 s … 5 min).
 *   Linear rather than exponential because the realistic worst case is a package update that
 *   resolves in a minute or two, and a doubling schedule would be at hours by the end of a dash.
 *   Inside the window the answer is [Decision.WAIT] — rebuilding an engine per offer would thrash
 *   the binder for nothing. Same shape as the #430 pipeline / #909 drain-worker supervision.
 * - **[NOTIFY_AFTER_FAILURES] consecutive failures ⇒ [Decision.NOTIFY], once per process.** At that
 *   point re-init has demonstrably not helped and the dasher deserves to hear it from the app
 *   rather than from a silent phone.
 *
 * ### State
 * A successful [onSpeakSuccess] clears the streak and the backoff — the next failure starts over at
 * "re-init immediately". The [Decision.NOTIFY] latch is deliberately NOT cleared: one notice per
 * process, the same contract the #937/#938 app notices hold, so a flapping engine can't turn a
 * disclosure into a nag. A process restart re-arms everything.
 *
 * Synchronized because [TtsEffectHandler] is a `@Singleton` whose `speakOffer` runs on the effect
 * drain worker; the counters must not tear even if a second caller ever appears.
 */
class TtsRecoveryPolicy {

    /** What the handler should do about the failure just reported. */
    enum class Decision {
        /** Tear the engine down and build a new one now. */
        RETRY_REINIT,

        /** A re-init is still inside its backoff window — do nothing but log. */
        WAIT,

        /** Recovery is not working: tell the dasher. Returned at most once per process. */
        NOTIFY,
    }

    private var consecutiveFailures = 0
    private var reinitAttempts = 0
    private var lastReinitAtMillis: Long? = null
    private var notified = false

    /** Consecutive failed utterances since the last success. Diagnostics/logging only. */
    val failureStreak: Int
        @Synchronized get() = consecutiveFailures

    /**
     * Record a failed utterance at [nowMillis] and decide what follows. Every call advances the
     * streak; only a [Decision.RETRY_REINIT] arms the next backoff window.
     */
    @Synchronized
    fun onSpeakFailure(nowMillis: Long): Decision {
        consecutiveFailures++

        if (!notified && consecutiveFailures >= NOTIFY_AFTER_FAILURES) {
            notified = true
            return Decision.NOTIFY
        }

        val last = lastReinitAtMillis
        if (last == null || elapsedAllowsReinit(last, nowMillis)) {
            lastReinitAtMillis = nowMillis
            reinitAttempts++
            return Decision.RETRY_REINIT
        }
        return Decision.WAIT
    }

    /** An utterance was accepted by the engine: the streak and the backoff reset. */
    @Synchronized
    fun onSpeakSuccess() {
        consecutiveFailures = 0
        reinitAttempts = 0
        lastReinitAtMillis = null
    }

    /** The backoff window that must elapse before the *next* re-init, given the attempts so far. */
    @Synchronized
    fun currentBackoffMillis(): Long =
        (BACKOFF_STEP_MILLIS * reinitAttempts).coerceAtMost(MAX_BACKOFF_MILLIS)

    /**
     * A wall clock can step backwards (NTP correction, manual set). A negative elapsed value must
     * re-anchor and allow the re-init rather than freeze recovery until the clock catches up —
     * fail toward trying again.
     */
    private fun elapsedAllowsReinit(lastMillis: Long, nowMillis: Long): Boolean {
        val elapsed = nowMillis - lastMillis
        return elapsed < 0L || elapsed >= currentBackoffMillis()
    }

    companion object {
        /** Backoff grows by this much per re-init already attempted. */
        const val BACKOFF_STEP_MILLIS = 30_000L

        /** Ceiling on the backoff — never wait longer than this between re-init attempts. */
        const val MAX_BACKOFF_MILLIS = 300_000L

        /** Consecutive failures after which the dasher is told (once per process). */
        const val NOTIFY_AFTER_FAILURES = 3
    }
}
