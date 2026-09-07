package cloud.trotter.dashbuddy.core.pipeline.rules

import com.google.re2j.Pattern as Re2Pattern

/**
 * The ONE place rule regexes are compiled (#418). Extracted from [RuleCompiler] (audit #11) so the
 * untrusted-rule regex boundary has a findable, independently-testable home — it is a load-time
 * security control, not incidental compiler plumbing.
 *
 * Two controls live here, and only two:
 *  - **bounded ingestion** — [RuleCompiler.MAX_REGEX_LENGTH] caps the pattern text;
 *  - **fail-loud parsing** — an unparseable pattern is a [RuleCompileException] at LOAD, never a
 *    surprise on the per-event hot path.
 *
 * Match time itself is no longer this object's problem. Rule patterns compile through [BoundedRegex]
 * onto RE2J, whose NFA simulation is linear in `input × pattern` and cannot backtrack (#1053) — so
 * the "accepted ⇒ bounded" invariant is a property of the engine rather than a guard in front of it.
 *
 * **The compile-time ReDoS heuristic is deliberately gone.** It rejected the nested-unbounded family
 * (`(a+)+`, `(.*)+`, `(\d+){2,}`), but a structural heuristic can never be sound — `(a|aa)+$`,
 * `(a?)*b` and `(.*a){20}` all sailed through it — and the runtime budget that was supposed to catch
 * the rest could not work on Android at all (see [BoundedRegex]). Keeping a heuristic in front of a
 * non-backtracking engine would be a second, weaker owner of a property the engine already
 * guarantees, rejecting *safe* patterns for a risk that no longer exists (Principle 5).
 *
 * Every regex compiled from rule JSON MUST go through [compileRegex]; nothing in the rule engine
 * constructs a matcher directly (ratcheted by a source-scan guard).
 */
internal object RegexSafety {

    /**
     * Compile a rule-supplied pattern into a case-insensitive [BoundedRegex], enforcing the length
     * cap first. Throws [RuleCompileException] on an over-long or unparseable pattern.
     *
     * The pattern language is **RE2 syntax**: no lookaround, no backreferences, no possessive or
     * atomic groups. That restriction is the whole point — it is what makes the linear-time bound a
     * theorem. A rule using an unsupported construct fails loud at load, per file.
     */
    fun compileRegex(pattern: String): BoundedRegex {
        if (pattern.length > RuleCompiler.MAX_REGEX_LENGTH)
            throw RuleCompileException(
                "Regex pattern length ${pattern.length} exceeds " +
                    "MAX_REGEX_LENGTH=${RuleCompiler.MAX_REGEX_LENGTH}",
            )
        return try {
            BoundedRegex(Re2Pattern.compile(pattern, Re2Pattern.CASE_INSENSITIVE))
        } catch (e: Exception) {
            throw RuleCompileException(
                "Invalid regex pattern: '$pattern' (rule patterns are RE2 syntax — no lookaround, " +
                    "no backreferences)",
                e,
            )
        }
    }
}
