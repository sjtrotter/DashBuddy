package cloud.trotter.dashbuddy.core.pipeline.rules

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #418 → #1053 — the ReDoS contract, inverted.
 *
 * This file used to assert that the nested-unbounded family (`(a+)+`, `(.*)+`, `(\d+){2,}`) was
 * REJECTED at compile time. That guard is gone, and its absence is the point: rule patterns now
 * compile onto RE2J, whose NFA simulation cannot backtrack, so the shapes the heuristic existed to
 * reject are simply *not dangerous* — and a heuristic that rejects safe patterns to defend against
 * a risk the engine already forecloses is a second, weaker owner of the same property (Principle 5).
 *
 * So the assertion flips: every catastrophic shape **compiles and matches within a hard wall-clock
 * bound**. That is the property #590 wanted and could not have — its 200 ms watchdog was
 * unenforceable on Android, where `Matcher.reset(CharSequence)` hands the match to native ICU (see
 * [BoundedRegex]). The bound asserted here is a property of the engine, so it holds identically on
 * ART; `RuleRegexIsLinearTimeTest` (`androidTest`) runs the headline case there for provenance.
 */
class RegexReDoSTest {

    private companion object {
        /**
         * Wall-clock ceiling for one match of a catastrophic shape. A backtracking engine on these
         * inputs runs for years, not milliseconds, so the gap between "linear" and "exponential" is
         * astronomical — 50 ms is a generous cushion for a cold JIT on shared CI, not a tight fit.
         */
        const val BUDGET_MS = 50L
    }

    /**
     * The soundness catalog: the shapes a structural heuristic cannot all catch. `(a+)+$` and
     * `(.*)+` were rejected by the old guard; `(a|aa)+$`, `(a?)*b` and `(.*a){20}` sailed past it
     * and would then have hung (or, on a long input, thrown `StackOverflowError` from the JDK
     * engine's per-repetition recursion). Every one of them is linear here.
     */
    private val catastrophic = listOf(
        "(a+)+\$",
        "(a*)*",
        "(a+)*",
        "(.*)+",
        "(\\d+){2,}",
        "((a+))+",
        "(?:a+)+",
        "(a+|b+)+",
        "(\\d+\\s*)+",
        "(a|aa)+\$",
        "(a?)*b",
        "(.*a){20}",
    )

    /** The classic pumping input: a run of the class the pattern chews on, then a non-match. */
    private fun pumping(n: Int) = "a".repeat(n) + "!"

    private fun assertMatchesWithinBudget(pattern: String, input: CharSequence, what: String) {
        val regex = RuleCompiler.compileRegex(pattern)
        // Warm the engine once so the assertion measures the match, not class loading.
        regex.containsMatchIn("a")
        val start = System.nanoTime()
        regex.containsMatchIn(input)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "$what: <$pattern> against ${input.length} chars took ${elapsedMs}ms, over the " +
                "${BUDGET_MS}ms linear-time budget",
            elapsedMs < BUDGET_MS,
        )
    }

    @Test
    fun `every catastrophic shape compiles - the heuristic that rejected them is retired`() {
        for (pattern in catastrophic) {
            try {
                RuleCompiler.compileRegex(pattern)
            } catch (e: RuleCompileException) {
                fail("<$pattern> must compile — RE2J cannot backtrack, so it is not a ReDoS: ${e.message}")
            }
        }
    }

    @Test
    fun `the headline exploit bounds its match - 64 a's and a bang`() {
        // The case #1053 says no host test could honestly write: on a backtracking engine
        // `(a|aa)+$` against 64 'a's and a '!' is 2^64 paths. Here it is a linear scan.
        assertMatchesWithinBudget("(a|aa)+\$", pumping(64), "headline exploit")
        assertMatchesWithinBudget("(a+)+\$", pumping(64), "headline exploit")
    }

    @Test
    fun `every catastrophic shape bounds its match on a pumping input`() {
        for (pattern in catastrophic) {
            assertMatchesWithinBudget(pattern, pumping(64), "soundness catalog")
        }
    }

    @Test
    fun `the bound holds at the ingestion ceiling - a MAX_REGEX_LENGTH input`() {
        // Bounded ingestion caps the PATTERN; the INPUT is a third-party node's text and is not
        // capped here, so pump it well past the pattern cap to show the bound is about the engine,
        // not about small inputs.
        val long = pumping(RuleCompiler.MAX_REGEX_LENGTH * 50)
        for (pattern in catastrophic) {
            assertMatchesWithinBudget(pattern, long, "long input")
        }
    }

    @Test
    fun `matches - the whole-input operation is bounded too`() {
        // #1029's nextSiblingMatchingRegex uses matches(), not containsMatchIn(); a bound on one
        // says nothing about the other, so assert it directly.
        val regex = RuleCompiler.compileRegex("(a|aa)+\$")
        regex.matches("a")
        val start = System.nanoTime()
        regex.matches(pumping(64))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("matches() took ${elapsedMs}ms, over the ${BUDGET_MS}ms budget", elapsedMs < BUDGET_MS)
    }

    @Test
    fun `linear patterns are accepted - including every shape the production rules use`() {
        val accepted = listOf(
            "(\\d+)",
            "(ab)+",
            "(?:abc)+",
            "\\d+\\.\\d+",
            "(a+){2}",
            "(a+)?",
            // Verbatim from the doordash/uber rulesets:
            "\\\$\\d+\\.\\d{2}",
            "\\d[\\d.]*\\s*mi",
            "^Going to (?:\\D|\$)", // #1053 — was `^Going to (?!\d)`; RE2 has no lookaround
            "To shop \\((\\d+)\\)",
            "\\bby\\s+\\d{1,2}:\\d{2}",
            "^\\d{1,5}\\s+\\S",
            "\\d{5}\$",
            "\\p{L}",
        )
        for (pattern in accepted) RuleCompiler.compileRegex(pattern)
    }

    @Test
    fun `a lookaround pattern fails the LOAD loudly rather than silently never matching`() {
        // The one syntax RE2 does not have. It must be a RuleCompileException at load — the
        // fail-closed shape the untrusted-rule path (#192/#640) depends on.
        try {
            RuleCompiler.compileRegex("^Going to (?!\\d)")
            fail("expected RuleCompileException — RE2 has no lookaround")
        } catch (e: RuleCompileException) {
            assertTrue(
                "the rejection must name the pattern language: ${e.message}",
                e.message!!.contains("RE2"),
            )
        }
    }
}
