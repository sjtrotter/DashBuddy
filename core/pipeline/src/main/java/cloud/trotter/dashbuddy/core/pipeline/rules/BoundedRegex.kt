package cloud.trotter.dashbuddy.core.pipeline.rules

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
 *    as well as on the host — and every host unit test of a rule pattern is now a faithful device
 *    test (one instrumented spot-check pins that for provenance);
 *  - the price is RE2 *syntax*: no lookaround, no backreferences. Rule authors get a language whose
 *    worst case is known, which is the language an untrusted CDN rule source (#192/#640) needs.
 *
 * Bounded **ingestion** is unchanged and still lives at the door: [RuleCompiler.MAX_REGEX_LENGTH]
 * caps the pattern, and an unparseable pattern is a loud [RuleCompileException] at load
 * ([RegexSafety.compileRegex]).
 *
 * Kotlin [Regex] never escapes this seam — [find] returns a [BoundedMatch], not a `MatchResult` —
 * so the engine behind the rule language stays swappable and no caller can reach a raw matcher.
 * Only rule-authored regexes are wrapped; app-authored constant patterns keep the plain hot path.
 */
class BoundedRegex internal constructor(private val pattern: Re2Pattern) {

    /** True if [input] contains a match anywhere (the 7 `…MatchesRegex` predicates). */
    fun containsMatchIn(input: CharSequence): Boolean = pattern.matcher(input).find()

    /**
     * WHOLE-input match (#1029) — the strict sibling of [containsMatchIn], for a rule that names
     * the exact shape a node's text must have rather than a substring it must contain
     * (`nextSiblingMatchingRegex`).
     */
    fun matches(input: CharSequence): Boolean = pattern.matcher(input).matches()

    /** The first match in [input], or null. */
    fun find(input: CharSequence): BoundedMatch? {
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

    /** The raw pattern string, for logging/debugging. */
    override fun toString(): String = pattern.pattern()
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
