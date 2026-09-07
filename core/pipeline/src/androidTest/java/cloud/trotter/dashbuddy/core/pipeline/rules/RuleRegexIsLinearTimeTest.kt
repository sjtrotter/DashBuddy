package cloud.trotter.dashbuddy.core.pipeline.rules

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The #1053 bound, asserted **on ART** — the one engine the retired watchdog could never reach.
 *
 * The whole reason the 200 ms budget was a fiction is that Android's `java.util.regex` is
 * ICU-backed: `Matcher.reset(CharSequence)` stringifies its input and runs the match natively, so
 * the interruptible sequence the watchdog depended on was never consulted and ICU exposes no
 * timeout to that API. A host unit test could therefore assert a bound the device did not have.
 *
 * RE2J is pure Java, so the same bytecode runs on the JVM and on ART and the host tests
 * (`RegexReDoSTest`, `RegexBudgetPropertyTest`) are already faithful. This file exists for
 * **provenance**, not coverage: it runs the headline exploit — `(a|aa)+$` against 64 `a`s and a
 * `!`, which is 2^64 backtracking paths on a conventional engine — through the production
 * `RuleCompiler.compileRegex` seam on a real device, so the claim "the bound holds on the device"
 * is something the repo has actually observed rather than reasoned about.
 *
 * Instrumented, so it does NOT gate the unit-only PR CI; it rides the emulator nightly (#939)
 * beside the migration tests.
 */
@RunWith(AndroidJUnit4::class)
class RuleRegexIsLinearTimeTest {

    private companion object {
        /**
         * Generous next to a linear scan of 65 characters (microseconds) and unreachably small for
         * an exponential one. An emulator under CI load is slow, hence 500 ms rather than the host
         * suite's 50 ms — the gap being measured is astronomical, so precision buys nothing.
         */
        const val BUDGET_MS = 500L

        val CATASTROPHIC = listOf("(a|aa)+\$", "(a+)+\$", "(a?)*b", "(.*a){20}", "(\\d+\\s*)+")
    }

    /** The classic pumping input: a run of the class the pattern chews on, then a non-match. */
    private val pumping = "a".repeat(64) + "!"

    @Test
    fun theHeadlineExploitBoundsItsMatchOnDevice() {
        for (pattern in CATASTROPHIC) {
            val regex = RuleCompiler.compileRegex(pattern)
            regex.containsMatchIn("a") // warm: measure the match, not class loading
            val start = System.nanoTime()
            regex.containsMatchIn(pumping)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(
                "<$pattern> took ${elapsedMs}ms on device, over the ${BUDGET_MS}ms linear-time " +
                    "bound — the rule engine is backtracking again (#1053)",
                elapsedMs < BUDGET_MS,
            )
        }
    }

    @Test
    fun theWholeInputOperationIsBoundedOnDeviceToo() {
        // #1029's nextSiblingMatchingRegex uses matches(), not containsMatchIn().
        val regex = RuleCompiler.compileRegex("(a|aa)+\$")
        regex.matches("a")
        val start = System.nanoTime()
        regex.matches(pumping)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("matches() took ${elapsedMs}ms on device", elapsedMs < BUDGET_MS)
    }

    @Test
    fun theProductionNameShapeCompilesAndMatchesOnDevice() {
        // The #885 first-last-initial shape is the most construct-heavy pattern the rulesets carry
        // (\p{L} inside a character class, bounded quantifiers, anchors). RE2 and ICU do not have
        // to agree about it any more — but it must still compile and behave on the device.
        val regex = RuleCompiler.compileRegex(
            "^\\s{0,8}[\\p{L}][\\p{L}'-]{0,20}(\\s{1,4}[\\p{L}][\\p{L}'-]{0,20}){0,3}\\s{1,4}[A-Z]\\.?\\s{0,8}\$",
        )
        assertTrue("the fielded double-space render must match", regex.containsMatchIn("Brandy  S."))
        assertTrue("an accented name must match", regex.containsMatchIn("José Muñoz M"))
        assertTrue(
            "a merchant line must NOT match",
            !regex.containsMatchIn("SPROUTS FARMERS MARKET #118"),
        )
    }
}
