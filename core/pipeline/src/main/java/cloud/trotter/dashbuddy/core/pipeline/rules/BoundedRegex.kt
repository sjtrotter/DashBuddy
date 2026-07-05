package cloud.trotter.dashbuddy.core.pipeline.rules

import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * A rule-authored [Regex] with a **runtime match-time budget** (#590).
 *
 * [RegexSafety] rejects the nested-unbounded ReDoS family at COMPILE time, but
 * its own KDoc admits Kotlin/Java [Regex] has **no match timeout**, and the
 * structural heuristic cannot catch every catastrophic shape — ambiguous
 * alternation (`(a|aa)+$`), optional-inside-star (`(a?)*b`), bounded-outer over
 * unbounded-inner (`(.*a){20}`), and backreference blowups (`(\w+)\1+`) all slip
 * through and then backtrack unbounded on the per-event classification thread.
 * A single such pattern (careless author today; hostile CDN rule once #192
 * opens) hangs recognition. This makes "accepted ⇒ bounded match time" true by
 * construction: the compile heuristic no longer has to be sound on its own.
 *
 * Catastrophic patterns fail two ways, and both are closed here:
 *  - **Exponential hang** (ambiguous alternation on a moderate input): the match
 *    runs on the calling thread against an [InterruptibleCharSequence]; a shared
 *    daemon watchdog interrupts that thread after [BUDGET_MS]. `Matcher` reads
 *    the input via `charAt` on every backtracking step, so the guarded sequence
 *    throws [RegexBudgetExceeded] the moment the interrupt lands.
 *  - **Stack overflow** (deep quantifier recursion on a long input): Java's
 *    regex engine recurses per repetition, so a long input overflows the stack
 *    with a [StackOverflowError] — an [Error] that would otherwise escape every
 *    `Exception`-only catch downstream. Caught here on the match thread.
 *
 * Either way the match **fails closed** — no-match (`false`/`null`), so the frame
 * simply doesn't recognize (→ UNKNOWN → scrubbed) rather than hanging or crashing
 * the thread — and one WARN fires (a defended invariant, no rule/PII text —
 * Principle 7). Well-formed matches finish in microseconds and cancel the
 * watchdog before it fires, so there is **zero behavior change** for the linear
 * patterns the production rules use.
 *
 * Only rule-authored regexes (everything from [RegexSafety.compileRegex]) are
 * wrapped; app-authored constant patterns keep the plain hot path.
 */
class BoundedRegex internal constructor(private val regex: Regex) {

    fun containsMatchIn(input: CharSequence): Boolean =
        runBounded(default = false, input) { regex.containsMatchIn(it) }

    fun find(input: CharSequence): MatchResult? =
        runBounded(default = null, input) { regex.find(it) }

    /** The underlying [Pattern] — compile-time introspection only (no match). */
    fun toPattern(): Pattern = regex.toPattern()

    /** The raw pattern string, for logging/debugging. */
    override fun toString(): String = regex.pattern

    private inline fun <T> runBounded(default: T, input: CharSequence, block: (CharSequence) -> T): T {
        val guarded = InterruptibleCharSequence(input)
        val self = Thread.currentThread()
        val watchdog = WATCHDOG.schedule({ self.interrupt() }, BUDGET_MS, TimeUnit.MILLISECONDS)
        return try {
            block(guarded)
        } catch (e: RegexBudgetExceeded) {
            Timber.tag("Pipeline").w(
                "Rule regex match aborted: exceeded %dms budget (ReDoS runtime guard, #590)",
                BUDGET_MS,
            )
            default
        } catch (e: StackOverflowError) {
            // Deep quantifier recursion on a long input (#590). An Error, so it
            // would escape every downstream Exception-only catch = fail open.
            // Treat as no-match — the match is a self-contained operation and the
            // stack has already unwound to here.
            Timber.tag("Pipeline").w("Rule regex match overflowed the stack (ReDoS runtime guard, #590)")
            default
        } finally {
            watchdog.cancel(false)
            // Clear any interrupt the watchdog set. The classification pipeline
            // is coroutine-based (cooperative cancellation, not thread-interrupt),
            // so nothing else on this thread relies on the interrupt flag.
            Thread.interrupted()
        }
    }

    private class RegexBudgetExceeded : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this // cheap: control-flow only
    }

    /**
     * Wraps a [CharSequence] so `charAt` throws [RegexBudgetExceeded] once the
     * current thread is interrupted — the hook the watchdog uses to abort a
     * runaway backtracking match. `get` maps to `CharSequence.charAt` on the JVM.
     */
    private inner class InterruptibleCharSequence(private val inner: CharSequence) : CharSequence {
        override val length: Int get() = inner.length
        override fun get(index: Int): Char {
            if (Thread.currentThread().isInterrupted) throw RegexBudgetExceeded()
            return inner[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            InterruptibleCharSequence(inner.subSequence(startIndex, endIndex))
        override fun toString(): String = inner.toString()
    }

    companion object {
        /**
         * Wall-clock budget for a single rule-authored match. Generous next to a
         * well-formed match (microseconds); small enough that a catastrophic
         * pattern can't stall the classification thread for a user-visible beat.
         */
        const val BUDGET_MS = 200L

        private val WATCHDOG: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "regex-redos-watchdog").apply { isDaemon = true }
            }
    }
}
