package cloud.trotter.dashbuddy.core.pipeline.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Focused unit test for the [RegexSafety] seam (audit #11, reshaped by #1053).
 *
 * After #1053 this object owns exactly two controls — the [RuleCompiler.MAX_REGEX_LENGTH] ingestion
 * cap and fail-loud parsing — so this file tests those, plus the [BoundedRegex] API contract the
 * rule engine's four call sites depend on. The match-TIME property moved to `RegexReDoSTest`, where
 * it is now asserted rather than approximated by a compile-time heuristic.
 */
class RegexSafetyTest {

    // =========================================================================
    // The two remaining load-time controls
    // =========================================================================

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

    @Test
    fun `an RE2-unsupported construct is a LOAD failure, not a silent never-match`() {
        // Lookaround and backreferences are the two things the linear-time guarantee costs. A rule
        // that reaches for either must fail the load loudly (fail-closed, the #192 CDN posture),
        // never compile into a matcher that quietly never fires.
        for (pattern in listOf("^Going to (?!\\d)", "(?=abc)x", "(\\w+)\\1+")) {
            try {
                RegexSafety.compileRegex(pattern)
                fail("expected RuleCompileException for RE2-unsupported pattern <$pattern>")
            } catch (e: RuleCompileException) {
                assertTrue(
                    "the rejection must name the pattern language: ${e.message}",
                    e.message!!.contains("RE2"),
                )
            }
        }
    }

    // =========================================================================
    // The BoundedRegex contract the four call sites rely on
    // =========================================================================

    @Test
    fun `compileRegex returns a usable case-insensitive matcher for a safe pattern`() {
        val regex = RegexSafety.compileRegex("\\\$\\d+\\.\\d{2}")
        assertNotNull(regex)
        assertTrue(regex.containsMatchIn("total \$12.34 due"))
        assertTrue("patterns compile CASE_INSENSITIVE", RegexSafety.compileRegex("abc").containsMatchIn("xABCy"))
    }

    @Test
    fun `matches is whole-input while containsMatchIn is a substring search`() {
        val regex = RegexSafety.compileRegex("\\d{3}")
        assertTrue(regex.containsMatchIn("ab123cd"))
        assertTrue(!regex.matches("ab123cd"))
        assertTrue(regex.matches("123"))
    }

    @Test
    fun `find exposes group values and ranges the way its Kotlin predecessor did`() {
        // TransformRegistry reads groupValues[n]; CompiledNotifRedact.maskGroup reads
        // groups[n].value + .range and feeds the range straight to String.replaceRange.
        val regex = RegexSafety.compileRegex("(\\d+)\\.(\\d+)")
        val m = regex.find("price 12.34 usd")!!
        assertEquals("12.34", m.value)
        assertEquals(6 until 11, m.range)
        assertEquals(listOf("12.34", "12", "34"), m.groupValues)
        assertEquals("12", m.groups[1]!!.value)
        assertEquals(6 until 8, m.groups[1]!!.range)
        assertEquals("34", "price 12.34 usd".substring(m.groups[2]!!.range))
    }

    @Test
    fun `a group that did not participate is null in groups and empty in groupValues`() {
        // Kotlin's MatchResult convention, which maskGroup's fail-closed null check depends on.
        val regex = RegexSafety.compileRegex("(a)|(b)")
        val m = regex.find("b")!!
        assertEquals(3, m.groupValues.size)
        assertNull(m.groups[1])
        assertEquals("", m.groupValues[1])
        assertEquals("b", m.groups[2]!!.value)
    }

    @Test
    fun `find returns null when nothing matches`() {
        assertNull(RegexSafety.compileRegex("\\d+").find("no digits here"))
    }

    @Test
    fun `groupCount reports the capturing groups without running a match`() {
        // RuleCompiler bounds a notification redact's maskGroup against this at COMPILE time.
        assertEquals(0, RegexSafety.compileRegex("abc").groupCount())
        assertEquals(2, RegexSafety.compileRegex("(\\d+)\\.(\\d+)").groupCount())
        assertEquals(1, RegexSafety.compileRegex("(?:x)(\\d+)").groupCount())
    }

    @Test
    fun `toString reports the raw pattern for logging`() {
        assertEquals("\\d{5}\$", RegexSafety.compileRegex("\\d{5}\$").toString())
    }
}
