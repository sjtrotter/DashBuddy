package cloud.trotter.dashbuddy.core.pipeline.rules

import timber.log.Timber
import com.google.re2j.Pattern as Re2Pattern

/**
 * A rule-authored regex whose match time is **bounded by construction** (#1053, superseding the
 * #590 watchdog).
 *
 * ## Why the old bound was false
 *
 * This class used to run a Kotlin [Regex] on the calling thread behind a 200 ms watchdog that
 * interrupted the thread, with an `InterruptibleCharSequence` whose `charAt` threw on the
 * interrupt. That works on the desktop JVM. It **cannot work on Android**: `Matcher.reset(
 * CharSequence)` stringifies its input and hands the match to native ICU, so the guarded sequence
 * is never consulted again and there is no ICU timeout API reachable from `java.util.regex` on
 * ART. The watchdog fired into a native match it could not stop — the "accepted ⇒ bounded" claim
 * held only on the host, i.e. exactly where it was never needed. Same class of host/device
 * divergence as #909, and the compile-time ReDoS heuristic that was supposed to back it up is
 * unsound by nature (`(a|aa)+$`, `(a?)*b`, `(.*a){20}` all pass it).
 *
 * ## The bound now
 *
 * Rule patterns compile to **RE2J** — a pure-Java RE2 engine that simulates an NFA instead of
 * backtracking. Match time is linear in `input.length × pattern.size`, with no exponential blowup
 * and no per-repetition recursion, so:
 *  - there is no catastrophic pattern to reject: `(a+)+$` against 64 `a`s and a `!` is a
 *    microsecond match, not a hang, which is why [RegexSafety] no longer carries a ReDoS heuristic;
 *  - the bound is a property of the engine rather than a promise about a timer, so it holds on ART
 *    as well as on the host. **Not the same as "a host test is a device test", though**: RE2J
 *    carries its own Unicode tables to both, so those agree — but **stack size and JIT do not**. A
 *    match that succeeds on a host thread can overflow on an ART coroutine thread, which is exactly
 *    the shape [evaluating] exists for. Host tests are faithful about *structure*; one instrumented
 *    spot-check pins the bound on ART for provenance, and the small-stack consumer tests
 *    (`RegexEvaluationFailureTest`) cover the depth axis the host would otherwise flatter;
 *  - the price is RE2 *syntax*: no lookaround, no backreferences. Rule authors get a language whose
 *    worst case is known, which is the language an untrusted CDN rule source (#192/#640) needs.
 *
 * ## What the engine does NOT bound, and who does
 *
 * Linear *match* time says nothing about *compile* cost, and RE2J 1.8 has no program-size ceiling —
 * `(a{1000}){1000}` is fifteen characters and 1 002 002 instructions. So bounded ingestion grew a
 * second half at the door ([RegexSafety]): the pattern-length cap
 * ([RuleCompiler.MAX_REGEX_LENGTH]) is joined by [RegexSafety.MAX_REPEAT] and
 * [RegexSafety.MAX_GROUP_DEPTH], a coarse pre-compile estimate, and — the actual bound —
 * [RegexSafety.MAX_PROGRAM_SIZE] **measured** on the compiled program with
 * `com.google.re2j.Pattern.programSize()`. An over-long, over-sized, too-deep or unparseable
 * pattern is a loud [RuleCompileException] at load. The same seam also translates the Perl classes
 * toward ART's Unicode classes, so a rule means roughly on the device what it meant before this
 * engine change (an approximation, with the residual differences listed there).
 *
 * The one runtime guard kept from #590 is the `StackOverflowError` catch — but it no longer
 * converts the failure to a default. See [evaluating] for why one Boolean answer cannot serve a
 * positive predicate, a negated predicate and a redaction selector alike, and what each boundary
 * does instead.
 *
 * Kotlin [Regex] never escapes this seam — [find] returns a [BoundedMatch], not a `MatchResult` —
 * so the engine behind the rule language stays swappable and no caller can reach a raw matcher.
 * Only rule-authored regexes are wrapped; app-authored constant patterns keep the plain hot path.
 */
class BoundedRegex internal constructor(private val pattern: Re2Pattern) {

    /** True if [input] contains a match anywhere (the 7 `…MatchesRegex` predicates). */
    fun containsMatchIn(input: CharSequence): Boolean =
        evaluating(this) { pattern.matcher(input).find() }

    /**
     * WHOLE-input match (#1029) — the strict sibling of [containsMatchIn], for a rule that names
     * the exact shape a node's text must have rather than a substring it must contain
     * (`nextSiblingMatchingRegex`).
     */
    fun matches(input: CharSequence): Boolean =
        evaluating(this) { pattern.matcher(input).matches() }

    /** The first match in [input], or null. */
    fun find(input: CharSequence): BoundedMatch? = evaluating(this) { findOrNull(input) }

    private fun findOrNull(input: CharSequence): BoundedMatch? {
        val m = pattern.matcher(input)
        if (!m.find()) return null
        val count = m.groupCount()
        val groups = ArrayList<BoundedGroup?>(count + 1)
        val values = ArrayList<String>(count + 1)
        for (i in 0..count) {
            val start = m.start(i)
            if (start < 0) {
                // A group that did not participate. Kotlin's MatchResult reports null in `groups`
                // and "" in `groupValues`; both callers depend on exactly that shape.
                groups.add(null)
                values.add("")
            } else {
                val text = m.group(i).orEmpty()
                groups.add(BoundedGroup(text, start until m.end(i)))
                values.add(text)
            }
        }
        return BoundedMatch(values[0], groups[0]!!.range, values, groups)
    }

    /**
     * Number of capturing groups — compile-time introspection only (no match). Replaces the old
     * `toPattern().matcher("").groupCount()`, which leaked a `java.util.regex.Pattern` out of the
     * seam for the sake of one integer.
     */
    fun groupCount(): Int = pattern.groupCount()

    /**
     * Instructions in the compiled RE2J program — the quantity [RegexSafety.MAX_PROGRAM_SIZE]
     * bounds, exposed so a test can state the corpus's real margin instead of asserting a
     * prediction of it.
     */
    fun programSize(): Int = pattern.programSize()

    /** The raw pattern string, for logging/debugging. */
    override fun toString(): String = pattern.pattern()

    companion object {
        /** One WARN per distinct pattern per process — a stack overflow is a condition, not an event. */
        private val warnedPatterns = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /**
         * Run a match and turn a [StackOverflowError] into a **distinguishable**
         * [RegexEvaluationFailed] (#1053 round 5).
         *
         * ## Why this is not a Boolean default any more
         *
         * Round 2 restored a `catch (StackOverflowError)` that returned the operation's default —
         * `false` for a predicate, `null` for a find. Round 4's review showed why one default
         * cannot serve every caller. `^((.?){100}){40}$` is seventeen characters, estimates at
         * 28 244, compiles to 16 084 instructions — both load-time gates accepted it — and
         * overflows in RE2J's `Machine.add` while matching a five-character input on 256 KiB,
         * 512 KiB **and 1 MiB** stacks (ART's coroutine threads are about 1 MiB). Put that pattern
         * in a screen rule's `redact[].find.hasTextMatchesRegex` and the `false` means
         * `CompiledRedact.maskNode` finds no matching entry and ships the node **RAW**. The default
         * that is fail-closed for a positive predicate is fail-OPEN for a redaction selector, and
         * silently so.
         *
         * So the failure is no longer swallowed here. It is raised as its own exception and each
         * boundary decides what its own safe answer is:
         *
         *  - **recognition** (`Ruleset.matchFirst`) — the rule does not match, and evaluation moves
         *    on to the next rule. A rule that cannot be evaluated cannot claim a frame.
         *  - **parse** (`TransformRegistry`, `ParseExpressionCompiler`, `nextSiblingMatchingRegex`)
         *    — the field is null. Fail-null beats fail-wrong (#745).
         *  - **redaction** (`CompiledRedact`, `CompiledNotifRedact`) — the whole node or field is
         *    masked with the plain `[redacted]` constant. A redact entry that cannot be evaluated
         *    must fail toward privacy; there is no reading of "we could not tell" that justifies
         *    shipping the raw value.
         *
         * [RegexSafety.MAX_PROGRAM_SIZE] is the other half of the fix: at 2 000 instructions this
         * particular shape no longer loads at all. The exception is what makes the residue — some
         * other deep-nullable-repetition pattern, on some smaller stack — safe rather than silent.
         *
         * The message carries the pattern's LENGTH and nothing else: a rule pattern can quote
         * screen text, and this line is INFO-adjacent (Principle 7).
         */
        internal inline fun <T> evaluating(regex: BoundedRegex, block: () -> T): T =
            try {
                block()
            } catch (e: StackOverflowError) {
                throw regex.evaluationFailed()
            }

        /** Builds the exception and WARNs once per pattern per process. Not inline — it is cold. */
        internal fun BoundedRegex.evaluationFailed(): RegexEvaluationFailed {
            val key = pattern.pattern()
            if (warnedPatterns.add(key)) {
                Timber.tag("Pipeline").w(
                    "Rule regex evaluation overflowed the stack (pattern length %d, program %d) — " +
                        "the caller decides its own safe answer; a redact entry masks the whole node (#1053)",
                    key.length,
                    pattern.programSize(),
                )
            }
            return RegexEvaluationFailed(key.length)
        }
    }
}

/**
 * A rule-authored regex could not be EVALUATED — the match itself blew the stack (#1053 round 5).
 *
 * Distinct from [RuleCompileException], which is a load-time rejection. This one happens on the hot
 * path, on a pattern that loaded cleanly, and it is deliberately **not** convertible to a single
 * default: see [BoundedRegex.evaluating] for why one Boolean answer cannot serve a positive
 * predicate, a negated predicate and a redaction selector alike.
 *
 * Carries the pattern's [patternLength] only — never its text, which can quote screen content
 * (Principle 7).
 */
class RegexEvaluationFailed internal constructor(val patternLength: Int) : RuntimeException(
    "rule regex evaluation failed (pattern length $patternLength)",
) {
    /** Control-flow only; the stack trace is the overflow's, not ours, and costs more than it tells. */
    override fun fillInStackTrace(): Throwable = this
}

/**
 * One capturing group of a [BoundedMatch]. Mirrors Kotlin's `MatchGroup` so the rule engine can
 * drop `kotlin.text.Regex` without changing any caller's arithmetic — [range] is `start until end`,
 * which is what `String.replaceRange` consumes at the redaction site.
 */
class BoundedGroup internal constructor(val value: String, val range: IntRange)

/**
 * The result of [BoundedRegex.find] — the rule engine's own match type, so that no Kotlin
 * `MatchResult` (and therefore no `java.util.regex` engine) escapes the [BoundedRegex] seam.
 *
 * [groupValues] and [groups] are both `groupCount + 1` long and follow Kotlin's conventions
 * exactly: index 0 is the whole match, a group that did not participate is `""` in [groupValues]
 * and `null` in [groups]. RE2J reports UTF-16 offsets, so [range] indexes the input string the way
 * every caller already assumes.
 */
class BoundedMatch internal constructor(
    val value: String,
    val range: IntRange,
    val groupValues: List<String>,
    val groups: List<BoundedGroup?>,
)
