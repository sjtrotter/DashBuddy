package cloud.trotter.dashbuddy.core.pipeline.rules

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Focused unit test for the extracted [RegexSafety] guard (audit #11). The
 * end-to-end ReDoS contract through the compiler is in `RegexReDoSTest` (via
 * `RuleCompiler.compileRegex`); this hits the extracted object directly so the
 * security unit has its own home and the catastrophic-backtracking detector is
 * exercised in isolation — including a pattern that WOULD hang the matcher if it
 * compiled.
 */
class RegexSafetyTest {

    // =========================================================================
    // assertNoCatastrophicBacktracking — direct
    // =========================================================================

    @Test
    fun `nested unbounded quantifier is flagged before any Regex is built`() {
        try {
            RegexSafety.assertNoCatastrophicBacktracking("(a+)+")
            fail("expected RuleCompileException for nested unbounded quantifier")
        } catch (e: RuleCompileException) {
            assertTrue(
                "error should name the ReDoS risk: ${e.message}",
                e.message!!.contains("ReDoS"),
            )
        }
    }

    @Test
    fun `linear pattern passes the structural check`() {
        // Must not throw — a group with an unbounded body that is not itself
        // quantified is linear.
        RegexSafety.assertNoCatastrophicBacktracking("(\\d+)\\.(\\d+)")
    }

    // =========================================================================
    // compileRegex — the guard short-circuits the hang
    // =========================================================================

    @Test
    fun `compileRegex rejects a catastrophic pattern fast instead of hanging at match time`() {
        // `(a+)+$` is the classic ReDoS exploit: matched against a long run of
        // 'a' followed by a non-'a', a real Regex backtracks exponentially and
        // hangs the per-event classification thread (Kotlin Regex has no match
        // timeout). The guard must reject it at COMPILE time, well under a
        // second — the assertion below would never return if the engine instead
        // tried to match the exploit string.
        val start = System.nanoTime()
        try {
            RegexSafety.compileRegex("(a+)+\$")
            fail("expected RuleCompileException (ReDoS) for (a+)+\$")
        } catch (e: RuleCompileException) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("guard should reject quickly, took ${elapsedMs}ms", elapsedMs < 1_000)
        }
    }

    @Test
    fun `compileRegex returns a usable case-insensitive Regex for a safe pattern`() {
        val regex = RegexSafety.compileRegex("\\\$\\d+\\.\\d{2}")
        assertNotNull(regex)
        assertTrue(regex.containsMatchIn("total \$12.34 due"))
    }

    @Test
    fun `compileRegex enforces the length cap owned by RuleCompiler`() {
        val tooLong = "a".repeat(RuleCompiler.MAX_REGEX_LENGTH + 1)
        try {
            RegexSafety.compileRegex(tooLong)
            fail("expected RuleCompileException for over-long pattern")
        } catch (e: RuleCompileException) {
            assertTrue(
                "error should name the length cap: ${e.message}",
                e.message!!.contains("MAX_REGEX_LENGTH"),
            )
        }
    }

    @Test
    fun `compileRegex rejects a syntactically invalid pattern`() {
        try {
            RegexSafety.compileRegex("[invalid(")
            fail("expected RuleCompileException for invalid regex")
        } catch (e: RuleCompileException) {
            assertTrue(e.message!!.contains("Invalid regex"))
        }
    }
}
