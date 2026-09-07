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
 *    as well as on the host — one engine on both, modulo the Unicode TABLE versions RE2J 1.8 and
 *    ART's ICU each ship (see [RegexSafety] for the residual list), so a host regex test is a
 *    faithful device test for structure and for everything but those edges; one instrumented
 *    spot-check pins the bound on ART for provenance;
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
 * The one runtime guard kept from #590 is the [failClosed] `StackOverflowError` catch — see its
 * KDoc for why a non-backtracking engine still gets one.
 *
 * Kotlin [Regex] never escapes this seam — [find] returns a [BoundedMatch], not a `MatchResult` —
 * so the engine behind the rule language stays swappable and no caller can reach a raw matcher.
 * Only rule-authored regexes are wrapped; app-authored constant patterns keep the plain hot path.
 */
class BoundedRegex internal constructor(private val pattern: Re2Pattern) {

    /** True if [input] contains a match anywhere (the 7 `…MatchesRegex` predicates). */
    fun containsMatchIn(input: CharSequence): Boolean =
        failClosed(false) { pattern.matcher(input).find() }

    /**
     * WHOLE-input match (#1029) — the strict sibling of [containsMatchIn], for a rule that names
     * the exact shape a node's text must have rather than a substring it must contain
     * (`nextSiblingMatchingRegex`).
     */
    fun matches(input: CharSequence): Boolean =
        failClosed(false) { pattern.matcher(input).matches() }

    /** The first match in [input], or null. */
    fun find(input: CharSequence): BoundedMatch? = failClosed(null) { findOrNull(input) }

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
        /**
         * Run a match and **fail closed on a [StackOverflowError]** (#1053 round 2).
         *
         * The #590 version of this class carried the same catch because the JDK engine recursed
         * once per repetition; the first #1053 pass removed it on the premise that a
         * non-backtracking engine cannot recurse deeply. That premise is not safe enough to bet a
         * subsystem on. RE2J walks the parsed tree recursively when it simplifies and compiles,
         * ART's thread stacks are smaller than the host JVM's, and a `StackOverflowError` is an
         * **`Error`** — it escapes every `catch (e: Exception)` downstream and would take the
         * classification coroutine with it, which is precisely the #909 failure mode
         * (`SideEffectEngine` catching `Exception` while an `Error` killed the drain worker) and
         * the #430 one (an unsupervised pipeline crash silencing all sensing).
         *
         * The evidence, corrected: round 2 recorded that a match-time overflow could not be
         * reproduced. Round 3's review **did** reproduce one — `((a?){200}){40}` matched against
         * the empty string overflows inside RE2J's own `Machine.add` on a 256 KiB thread stack. So
         * this catch is load-bearing, not merely precautionary, and the round-2 note that said
         * otherwise was wrong. [RegexSafety.MAX_GROUP_DEPTH] bounds the same risk at load, from the
         * other side; the reproduction is stack-size dependent, so it is asserted through this seam
         * rather than as a pattern that must actually overflow.
         *
         * The match fails closed: no-match (`false`/`null`), so the frame simply does not recognize
         * (→ UNKNOWN → scrubbed) rather than crashing the thread, and one WARN fires — a defended
         * invariant, no rule or PII text (Principle 7).
         *
         * `internal` and `inline` so the fail-closed contract is unit-testable directly, without a
         * pattern that has to actually overflow a real stack to exercise it.
         */
        internal inline fun <T> failClosed(default: T, block: () -> T): T =
            try {
                block()
            } catch (e: StackOverflowError) {
                Timber.tag("Pipeline").w(
                    "Rule regex match overflowed the stack — failing closed to no-match (#1053)",
                )
                default
            }
    }
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
 * and `null` in [groups].
 */
class BoundedMatch internal constructor(
    val value: String,
    val range: IntRange,
    val groupValues: List<String>,
    val groups: List<BoundedGroup?>,
)
