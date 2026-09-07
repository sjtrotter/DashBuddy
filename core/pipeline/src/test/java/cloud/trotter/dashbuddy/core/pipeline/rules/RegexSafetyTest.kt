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
        // The TRANSLATED pattern is what the engine holds, so that is what it reports.
        assertEquals("\\p{Nd}{5}\$", RegexSafety.compileRegex("\\d{5}\$").toString())
    }

    // =========================================================================
    // #1053 round 2 / F1 — compiled program size (RE2J 1.8 has no cap of its own)
    // =========================================================================

    private fun assertRejected(pattern: String, mustMention: String) {
        try {
            RegexSafety.compileRegex(pattern)
            fail("expected RuleCompileException for <$pattern>")
        } catch (e: RuleCompileException) {
            assertTrue(
                "rejection should mention '$mustMention': ${e.message}",
                e.message!!.contains(mustMention),
            )
        }
    }

    @Test
    fun `a single counted repeat over MAX_REPEAT is rejected`() {
        // 15 characters, 1 002 002 instructions, and a 23-char sibling exhausts a capped heap AT
        // LOAD — on the device, once per rule. The length cap cannot see it.
        assertRejected("(a{1000}){1000}", "MAX_REPEAT")
        assertRejected("a{1000}", "MAX_REPEAT")
        assertRejected("a{0,201}", "MAX_REPEAT")
        assertRejected("a{201,}", "MAX_REPEAT")
        assertRejected("(a{201}){2}", "MAX_REPEAT")
    }

    @Test
    fun `nested counted repeats over MAX_REPEAT_PRODUCT are rejected`() {
        // Each factor is legal on its own; the PRODUCT is what the compiler expands.
        assertRejected("((a{50}){50}){50}", "MAX_REPEAT_PRODUCT") // 125 000 — the shape that OOMs
        assertRejected("((a{21}){20}){20}", "MAX_REPEAT_PRODUCT") // 8 400
        assertRejected("(a{129}){64}", "MAX_REPEAT_PRODUCT") // 8 256 — just over the cap
    }

    @Test
    fun `sequential repeats add, only NESTING multiplies`() {
        // The arithmetic the cap encodes: a concatenation grows the program by ADDITION, and the
        // pattern's own length already bounds how much of that can fit. `((a{16}){16}){16}` is
        // 8 192 — exactly at the cap, and legal — while putting more repeated atoms BESIDE a
        // capped group is not a multiplication at all.
        RegexSafety.compileRegex("((a{16}){16}){16}")
        RegexSafety.compileRegex("(a{128}){64}b{2}c+d{50}") // 8 192 — exactly at the cap
    }

    @Test
    fun `the product arithmetic admits what it should`() {
        RegexSafety.compileRegex("(a{50}){50}")   // 2 500
        RegexSafety.compileRegex("(a{64}){64}")   // 8 192 — exactly at the cap
        RegexSafety.compileRegex("a{64}")
        // An unbounded quantifier counts as MAX_REPEAT, so the ReDoS catalog stays LEGAL: on a
        // non-backtracking engine these are safe, and rejecting them would resurrect the heuristic.
        for (p in listOf("(a+)+\$", "(a*)*", "(.*)+", "(a|aa)+\$", "(a?)*b", "(.*a){20}", "(\\d+\\s*)+")) {
            RegexSafety.compileRegex(p)
        }
    }

    @Test
    fun `a literal brace is not a repeat`() {
        // `{` that opens no valid quantifier body is an ordinary character; it must not be read as
        // a repeat (and must not throw).
        RegexSafety.compileRegex("a\\{b\\}")
        RegexSafety.compileRegex("\\p{L}{3}")
        assertTrue(RegexSafety.compileRegex("\\p{L}{3}").matches("abc"))
    }

    // =========================================================================
    // #1053 round 2 / F2 — nesting depth + the fail-closed StackOverflowError catch
    // =========================================================================

    @Test
    fun `a deeply nested pattern is rejected at load`() {
        val deep = "(".repeat(60) + "a" + ")".repeat(60)
        assertTrue("the probe must fit the length cap", deep.length <= RuleCompiler.MAX_REGEX_LENGTH)
        assertRejected(deep, "MAX_GROUP_DEPTH")
    }

    @Test
    fun `nesting at the cap is accepted`() {
        val atCap = "(".repeat(RegexSafety.MAX_GROUP_DEPTH) + "a" + ")".repeat(RegexSafety.MAX_GROUP_DEPTH)
        assertTrue(RegexSafety.compileRegex(atCap).containsMatchIn("a"))
    }

    @Test
    fun `a match that overflows the stack fails closed instead of killing the thread`() {
        // A StackOverflowError is an Error: it escapes every `catch (e: Exception)` downstream and
        // would take the classification coroutine with it (#909/#430). The guard is asserted
        // through the seam rather than through a pattern that has to actually overflow a real
        // stack — see BoundedRegex.failClosed for why the catch is kept despite not being
        // reproducible on the host.
        assertEquals(false, BoundedRegex.failClosed(false) { throw StackOverflowError() })
        assertNull(BoundedRegex.failClosed<String?>(null) { throw StackOverflowError() })
        assertEquals("ok", BoundedRegex.failClosed("nope") { "ok" })
    }

    @Test
    fun `an ordinary exception is NOT swallowed by the fail-closed guard`() {
        // Fail-closed is for the Error class specifically. A programming bug must still surface.
        try {
            BoundedRegex.failClosed(false) { throw IllegalStateException("boom") }
            fail("expected the exception to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    // =========================================================================
    // #1053 round 2 / F3 — Perl classes translate to Unicode-aware RE2 classes
    // =========================================================================

    @Test
    fun `the translation table`() {
        assertEquals("\\p{Nd}", RegexSafety.prepare("\\d"))
        assertEquals("\\P{Nd}", RegexSafety.prepare("\\D"))
        assertEquals("[\\s\\p{Z}]", RegexSafety.prepare("\\s"))
        assertEquals("[^\\s\\p{Z}]", RegexSafety.prepare("\\S"))
        assertEquals("[\\p{L}\\p{N}_]", RegexSafety.prepare("\\w"))
        assertEquals("[^\\p{L}\\p{N}_]", RegexSafety.prepare("\\W"))
    }

    @Test
    fun `inside a character class the un-bracketed forms are emitted`() {
        assertEquals("[\\p{Nd}.]", RegexSafety.prepare("[\\d.]"))
        assertEquals("[\\s\\p{Z}:]", RegexSafety.prepare("[\\s:]"))
        assertEquals("[\\p{L}\\p{N}_-]", RegexSafety.prepare("[\\w-]"))
        assertEquals("[^\\p{Nd}]", RegexSafety.prepare("[^\\d]"))
        assertEquals("[\\P{Nd}x]", RegexSafety.prepare("[\\Dx]"))
    }

    @Test
    fun `a literal escape is never mistaken for a class`() {
        // `\\d` is a backslash then a literal 'd', not the digit class.
        assertEquals("\\\\d", RegexSafety.prepare("\\\\d"))
        // A braced escape is one atom and its braces are not a quantifier.
        assertEquals("\\p{Nd}", RegexSafety.prepare("\\p{Nd}"))
        assertEquals("\\x{1F600}", RegexSafety.prepare("\\x{1F600}"))
    }

    @Test
    fun `negated unions inside a class are rejected rather than silently left ASCII`() {
        assertRejected("[\\S]", "character class")
        assertRejected("[a\\W]", "character class")
    }

    @Test
    fun `an unsupported group construct is rejected by name`() {
        assertRejected("(?=abc)x", "RE2")
        assertRejected("(?<=a)b", "RE2")
        assertRejected("(?P<name>a)", "RE2")
        // `(?:` and flag groups survive.
        RegexSafety.compileRegex("(?:abc)+")
        RegexSafety.compileRegex("(?i)abc")
    }

    @Test
    fun `the fielded regressions - Android's Unicode classes are preserved`() {
        // (1) The #885 first-last-initial NAME shape separates tokens with `\s{1,4}`. On ART, `\s`
        //     includes \p{Z}, so a name rendered with a non-breaking space matched and the redact
        //     entry fired. Left ASCII, the mask would silently stop firing — a Pledge leak the host
        //     corpus cannot see.
        val name = RegexSafety.compileRegex(
            "^\\s{0,8}[\\p{L}][\\p{L}'-]{0,20}(\\s{1,4}[\\p{L}][\\p{L}'-]{0,20}){0,3}\\s{1,4}[A-Z]\\.?\\s{0,8}\$",
        )
        assertTrue(name.containsMatchIn("Brandy S"))
        assertTrue(name.containsMatchIn("Brandy S."))
        assertTrue("the fielded double-space render (#885)", name.containsMatchIn("Brandy  S."))
        assertTrue("a NON-BREAKING space must still be a separator", name.containsMatchIn("Brandy\u00a0S."))
        assertTrue("a THIN space too", name.containsMatchIn("Brandy\u2009S."))
        assertTrue("a merchant line must not match", !name.containsMatchIn("SPROUTS FARMERS MARKET #118"))

        // (2) Uber's two `Going to ...` notification rules discriminate on `\d`. Left ASCII, an
        //     address in non-ASCII digits falls from the address-MASKING dropoff rule to the
        //     redact-LESS pickup rule, and CustomerTextMarkers deliberately excludes "Going to ".
        val dropoff = RegexSafety.compileRegex("^Going to \\d")
        val pickup = RegexSafety.compileRegex("^Going to (?:\\D|\$)")
        assertTrue("an Arabic-Indic address is still a dropoff", dropoff.containsMatchIn("Going to \u0661\u0662\u0663 Main St"))
        assertTrue("...and must NOT fall to the redact-less pickup rule", !pickup.containsMatchIn("Going to \u0661\u0662\u0663 Main St"))
        assertTrue(dropoff.containsMatchIn("Going to 123 Main St"))
        assertTrue(!dropoff.containsMatchIn("Going to Chipotle"))
        assertTrue(pickup.containsMatchIn("Going to Chipotle"))
        assertTrue(pickup.containsMatchIn("Going to "))
    }

    @Test
    fun `the money scan widens to Unicode digits and parseGlyphCurrency then fails null`() {
        // CurrencyShape's `\d` become Unicode, so a MIXED figure now satisfies the rule-side scan
        // (its leading `[1-9]` is a literal ASCII range and stays ASCII by construction). That is
        // the correct division of labour: the SCAN finds a money-shaped node, and #1052's
        // code-point rejection in parseGlyphCurrency is what refuses to read a figure it cannot
        // read — fail-null, never a fabricated number.
        val scan = RegexSafety.compileRegex(CurrencyShape.RULE_PATTERN)
        assertTrue(scan.matches("\$16.70"))
        assertTrue("mixed-script decimals now reach the scan", scan.matches("\$16.\u0667\u0660"))
        assertTrue("the leading digit stays an ASCII range", !scan.matches("\$\u0661\u0666.70"))
        assertTrue("a leading zero is still out of shape", !scan.matches("\$016.70"))

        assertEquals(16.70, TransformRegistry.apply("parseGlyphCurrency", "This dash so far\$16.70"))
        assertNull(
            "a figure with a non-ASCII digit must read as NOTHING, not as 16.70",
            TransformRegistry.apply("parseGlyphCurrency", "This dash so far\$16.\u0667\u0660"),
        )
    }

    @Test
    fun `the length cap is measured on the pattern as written, not after translation`() {
        // Translation makes patterns longer (`\s` -> `[\s\p{Z}]`), and what an author writes is
        // what the schema validates and what a rule review reads.
        val allWhitespace = "\\s".repeat(RuleCompiler.MAX_REGEX_LENGTH / 2)
        assertEquals(RuleCompiler.MAX_REGEX_LENGTH, allWhitespace.length)
        val compiled = RegexSafety.compileRegex(allWhitespace) // must NOT trip the cap
        assertTrue(compiled.toString().length > RuleCompiler.MAX_REGEX_LENGTH)
    }
}
