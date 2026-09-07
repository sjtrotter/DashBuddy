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

    // -------------------------------------------------------------------------
    // Gate 2 — the MEASURED bound. Round 3 tried to bound program size with a
    // structural walk and the review defeated it three ways; the compiled program
    // is now simply asked how big it is (#1053 round 4).
    // -------------------------------------------------------------------------

    @Test
    fun `the reviewer's counterexamples are all rejected`() {
        // Each of these passed round 3's product ceiling. The instruction counts are measured
        // against the RE2J 1.8 jar, so they are facts about the engine, not estimates.

        // 200 chars, group depth 2, product exactly 8 192 — and 1 548 418 instructions (~88 MiB,
        // ~131 ms). A long literal packs far more atoms into the length cap than a product can see.
        assertRejected("((" + "a".repeat(187) + "){128}){64}", "instructions")

        // A standalone flag group emits nothing and introduces no atom, so the `{200}` after it
        // applies to `x{200}`. Round 3 settled the pending atom away and scored this 200.
        assertRejected("x{200}(?i){200}", "instructions") // 40 002 actual

        // `{0,}` means `*`, so its factor is 1 — not 0, which zeroed the product.
        assertRejected("((x{200}){0,}){200}", "instructions") // 41 002 actual
    }

    @Test
    fun `both gates are live - one pattern is caught by each`() {
        // The pre-compile estimate catches what would be expensive to COMPILE...
        val huge = "((" + "a".repeat(187) + "){128}){64}"
        assertTrue(
            "the estimate must exceed its ceiling for this one",
            RegexSafety.estimateInstructions(huge) > RegexSafety.MAX_ESTIMATED_INSTRUCTIONS,
        )
        assertRejected(huge, "MAX_ESTIMATED_INSTRUCTIONS")

        // ...and the MEASURED gate catches what slips past it. This is the case that proves the
        // measurement is doing real work rather than shadowing the estimate.
        val slipsEstimate = "x{200}(?i){200}"
        assertTrue(
            "this one must PASS the estimate, or it proves nothing about the measured gate",
            RegexSafety.estimateInstructions(slipsEstimate) <= RegexSafety.MAX_ESTIMATED_INSTRUCTIONS,
        )
        assertRejected(slipsEstimate, "MAX_PROGRAM_SIZE")
    }

    @Test
    fun `the estimate is charged against the corpus's real shapes`() {
        // Pinned so the next person can see the margin rather than trust it. `estimate` is the
        // pre-compile guard's number; `actual` is `Pattern.programSize()` measured on the jar.
        // The estimate is APPROXIMATE and may sit either side of the actual — that is why the
        // measured gate exists and why the estimate's ceiling is two orders of magnitude away.
        data class Case(val what: String, val pattern: String, val actual: Int)
        val corpus = listOf(
            Case(
                "#885 first-last-initial name shape",
                "^\\s{0,8}[\\p{L}][\\p{L}'-]{0,20}(\\s{1,4}[\\p{L}][\\p{L}'-]{0,20}){0,3}\\s{1,4}[A-Z]\\.?\\s{0,8}\$",
                240,
            ),
            Case(
                "payout store-name shape",
                "^.{1,60} \\((\\d{2,6}(-\\d{1,6})?|[A-Z][A-Za-z .'&-]+)\\)\$",
                157,
            ),
            Case(
                "store pair shape",
                "^[A-Z][\\w .'&-]{1,48} - [A-Z][\\w .'&-]{1,48}\$",
                199,
            ),
        )
        for (c in corpus) {
            val estimate = RegexSafety.estimateInstructions(c.pattern)
            assertTrue(
                "${c.what}: estimate $estimate must clear MAX_ESTIMATED_INSTRUCTIONS=" +
                    "${RegexSafety.MAX_ESTIMATED_INSTRUCTIONS} by a wide margin — round 3's " +
                    "product-of-all-bounds formula scored this shape 109 363 200 and would have " +
                    "rejected a ${c.actual}-instruction pattern",
                estimate < RegexSafety.MAX_ESTIMATED_INSTRUCTIONS / 100,
            )
            // And it really compiles to what the KDoc says it does.
            RegexSafety.compileRegex(c.pattern)
        }
    }

    @Test
    fun `a single counted repeat bound over MAX_REPEAT is rejected`() {
        assertRejected("a{201}", "MAX_REPEAT")
        assertRejected("a{0,201}", "MAX_REPEAT")
        assertRejected("a{201,}", "MAX_REPEAT")
    }

    @Test
    fun `a leading-zero bound is rejected rather than silently becoming literal text`() {
        // RE2's counted-repeat grammar refuses leading zeros, so RE2J reads `a{0201}` as the
        // LITERAL TEXT "a{0201}" — the author's intent and the engine's reading diverge with no
        // error anywhere. Refusing the ambiguity is the only reading that cannot surprise someone.
        assertRejected("a{0201}", "leading")
        assertRejected("a{1,020}", "leading")
        RegexSafety.compileRegex("a{0,20}") // a genuine zero minimum is fine
    }

    @Test
    fun `quoting is rejected outright`() {
        // \Q...\E is opaque to every load-time guard: the quoted `[` below opened a phantom
        // character class in round 3's scan and hid the repeats entirely (1 002 003 instructions).
        // And translating inside a quoted region is wrong anyway — `\Q\d\E` means the literal
        // text `\d`, which the class translation would turn into the literal text `\p{Nd}`.
        assertRejected("\\Q\\d\\E", "quoting")
        assertRejected("\\Q[\\E(a{1000}){1000}", "quoting")
        assertRejected("abc\\Qx\\E", "quoting")
    }

    @Test
    fun `the catastrophic family stays legal - the caps bound the COMPILER, not the matcher`() {
        // An unbounded quantifier is a loop over one copy of its body, so it multiplies nothing.
        // On a non-backtracking engine these are safe (RegexReDoSTest measures them), and
        // rejecting them would resurrect the #418 heuristic under a new name.
        for (p in listOf("(a+)+\$", "(a*)*", "(.*)+", "(a|aa)+\$", "(a?)*b", "(.*a){20}", "(\\d+\\s*)+")) {
            RegexSafety.compileRegex(p)
        }
        RegexSafety.compileRegex("(a{50}){50}")
        RegexSafety.compileRegex("a{200}")
    }

    @Test
    fun `a scoped flag group is accepted`() {
        // `(?i:...)` is valid RE2 syntax and an ordinary group; round 3 rejected it as unsupported.
        val scoped = RegexSafety.compileRegex("(?i:abc)")
        assertTrue(scoped.containsMatchIn("ABC"))
        assertTrue(RegexSafety.compileRegex("x(?i:abc)y").containsMatchIn("xABCy"))
        // The standalone form still works too.
        assertTrue(RegexSafety.compileRegex("(?i)abc").containsMatchIn("ABC"))
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
        // would take the classification coroutine with it (#909/#430). Round 2 said this could not
        // be reproduced; the round-3 review DID reproduce it — `((a?){200}){40}` against "" 
        // overflows inside RE2J's `Machine.add` on a 256 KiB thread stack. So the catch is load-
        // bearing, not merely defensive. Asserted through the seam rather than through a pattern
        // that has to actually overflow a real stack, because that reproduction is stack-size
        // dependent and would be a flaky test.
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
        assertEquals("[\\s\\p{Z}\\x{0B}\\x{85}]", RegexSafety.prepare("\\s"))
        assertEquals("[^\\s\\p{Z}\\x{0B}\\x{85}]", RegexSafety.prepare("\\S"))
        assertEquals("[\\p{L}\\p{M}\\p{N}\\p{Pc}]", RegexSafety.prepare("\\w"))
        assertEquals("[^\\p{L}\\p{M}\\p{N}\\p{Pc}]", RegexSafety.prepare("\\W"))
    }

    @Test
    fun `inside a character class the un-bracketed forms are emitted`() {
        assertEquals("[\\p{Nd}.]", RegexSafety.prepare("[\\d.]"))
        assertEquals("[\\s\\p{Z}\\x{0B}\\x{85}:]", RegexSafety.prepare("[\\s:]"))
        assertEquals("[\\p{L}\\p{M}\\p{N}\\p{Pc}-]", RegexSafety.prepare("[\\w-]"))
        assertEquals("[^\\p{Nd}]", RegexSafety.prepare("[^\\d]"))
        assertEquals("[\\P{Nd}x]", RegexSafety.prepare("[\\Dx]"))
    }

    @Test
    fun `a leading closing bracket is a class MEMBER, not the terminator`() {
        // `[]\s]` is "a `]`, or whitespace". Round 3's scan ended the class at that first `]` and
        // emitted `[][\s\p{Z}]]`, which stopped matching a space and started matching a space
        // FOLLOWED BY `]` — a silent change of meaning that compiled cleanly, so no exception
        // handling could have caught it (finding 5).
        assertEquals("[]\\s\\p{Z}\\x{0B}\\x{85}]", RegexSafety.prepare("[]\\s]"))
        val cls = RegexSafety.compileRegex("[]\\s]")
        assertTrue("must still match a bare space", cls.matches(" "))
        assertTrue("must still match a bare ]", cls.matches("]"))
        assertTrue("must NOT match space-then-bracket", !cls.matches(" ]"))
        // The negated form has the same rule: `[^]x]` is "not `]` and not `x`".
        assertEquals("[^]x]", RegexSafety.prepare("[^]x]"))
    }

    @Test
    fun `a POSIX class inside a character class is opaque`() {
        // `[[:alpha:]\s]`'s inner `]` closes the POSIX name, not the enclosing class. Round 3 read
        // it as the terminator and emitted `[[:alpha:][\s\p{Z}]]`, which stopped matching `a` and
        // started matching `a]`.
        assertEquals("[[:alpha:]\\s\\p{Z}\\x{0B}\\x{85}]", RegexSafety.prepare("[[:alpha:]\\s]"))
        val cls = RegexSafety.compileRegex("[[:alpha:]\\s]")
        assertTrue("must still match a letter", cls.matches("a"))
        assertTrue("must still match whitespace", cls.matches(" "))
        assertTrue("must NOT match letter-then-bracket", !cls.matches("a]"))
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
        // Round 4: VT and NEL are in Android's `\s` but not in `\p{Z}`, so the round-2 translation
        // silently dropped them. Added explicitly (finding 4).
        assertTrue("NEL (U+0085) is whitespace to Android", name.containsMatchIn("Brandy\u0085S."))
        assertTrue("VT (U+000B) is whitespace to Android", name.containsMatchIn("Brandy\u000bS."))
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
    fun `a word-class shape accepts a decomposed name and a connector`() {
        // The shipped merchant shape at dropoff.json5 uses `\w`. Android's `\w` includes combining
        // marks and connector punctuation; `[\p{L}\p{N}_]` (round 2) did not, so "Cafe" + U+0301
        // stopped matching on the device — a rule silently narrowing where the host could not see
        // it (finding 4).
        val pair = RegexSafety.compileRegex("^[A-Z][\\w .'&-]{1,48} - [A-Z][\\w .'&-]{1,48}\$")
        assertTrue("a decomposed accent must match", pair.containsMatchIn("Cafe\u0301 - Market"))
        assertTrue("the plain form still matches", pair.containsMatchIn("Cafe - Market"))
        val word = RegexSafety.compileRegex("^\\w+\$")
        assertTrue("connector punctuation is a word char to Android", word.matches("a\u203fb"))
        assertTrue("underscore still is", word.matches("a_b"))
        assertTrue("a hyphen still is not", !word.matches("a-b"))
    }

    @Test
    fun `the translation is an APPROXIMATION - the known residuals are pinned`() {
        // Stated, not claimed away: RE2J 1.8 and ART's ICU ship different Unicode table versions,
        // so exact parity is unreachable. These assertions exist so the residuals are visible and
        // a future RE2J bump that closes one of them is noticed rather than silently absorbed.
        val digit = RegexSafety.compileRegex("^\\d\$")
        assertTrue("ASCII digits, obviously", digit.matches("5"))
        assertTrue("Arabic-Indic digits — the case that motivated the translation", digit.matches("\u0661"))
        assertTrue(
            "RESIDUAL: U+1E951 ADLAM DIGIT ONE is a decimal digit to Android and not to RE2J 1.8. " +
                "If this starts passing, RE2J's tables caught up — update the residual list.",
            !digit.matches("\ud83a\udd51"),
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
