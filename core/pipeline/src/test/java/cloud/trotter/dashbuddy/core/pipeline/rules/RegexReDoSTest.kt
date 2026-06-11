package cloud.trotter.dashbuddy.core.pipeline.rules

import org.junit.Assert.fail
import org.junit.Test

/**
 * #418 — catastrophic-backtracking regex must be rejected at COMPILE time.
 * Kotlin [Regex] has no match timeout and recognition runs regex on the
 * per-event hot path, so a single nested-unbounded pattern would hang the
 * classification thread. The detector is conservative: it rejects the
 * nested-unbounded family and accepts the linear patterns the production
 * rules actually use.
 */
class RegexReDoSTest {

    private fun assertRejected(pattern: String) {
        try {
            RuleCompiler.compileRegex(pattern)
            fail("expected RuleCompileException (ReDoS) for: $pattern")
        } catch (e: RuleCompileException) {
            // expected
        }
    }

    private fun assertAccepted(pattern: String) {
        // Throws if the detector false-positives or the pattern is invalid.
        RuleCompiler.compileRegex(pattern)
    }

    @Test
    fun `nested unbounded quantifiers are rejected`() {
        assertRejected("(a+)+")
        assertRejected("(a*)*")
        assertRejected("(a+)*")
        assertRejected("(.*)+")
        assertRejected("(.+)*")
        assertRejected("(\\d+){2,}")        // unbounded outer {n,} over unbounded inner
        assertRejected("(a+)+\$")
        assertRejected("((a+))+")           // unbounded buried one group deeper
        assertRejected("(?:a+)+")           // non-capturing group, same risk
        assertRejected("(a+|b+)+")          // unbounded inside an alternation branch
        assertRejected("(\\d+\\s*)+")       // the realistic "repeat a padded number" footgun
    }

    @Test
    fun `linear patterns are accepted - including every shape the production rules use`() {
        assertAccepted("(\\d+)")            // group with unbounded inside but NOT quantified
        assertAccepted("(ab)+")             // quantified group, bounded body
        assertAccepted("(?:abc)+")
        assertAccepted("\\d+\\.\\d+")
        assertAccepted("(a+){2}")           // BOUNDED outer over unbounded inner — not catastrophic
        assertAccepted("(a+)?")             // optional, bounded
        // Verbatim from doordash.json / uber.json:
        assertAccepted("\\\$\\d+\\.\\d{2}")
        assertAccepted("\\d[\\d.]*\\s*mi")
        assertAccepted("^Going to (?!\\d)")
        assertAccepted("To shop \\((\\d+)\\)")
        assertAccepted("\\bby\\s+\\d{1,2}:\\d{2}")
        assertAccepted("^\\d{1,5}\\s+\\S")
        assertAccepted("\\d{5}\$")
    }
}
