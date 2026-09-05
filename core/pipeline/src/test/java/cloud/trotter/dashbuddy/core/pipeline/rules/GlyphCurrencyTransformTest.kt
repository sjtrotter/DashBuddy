package cloud.trotter.dashbuddy.core.pipeline.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `parseGlyphCurrency` — the animated digit-wheel reader (#1029).
 *
 * DoorDash 8.93.7 removed every money view id and now renders each figure as per-glyph, id-less
 * `TextView`s beside a label. `read: allText` joins a subtree with the EMPTY separator, so the
 * whole container arrives as one fused string. These cases pin the two properties the money side
 * depends on: a SETTLED wheel reconstructs exactly, and anything else is null — never a number.
 *
 * The three garbage shapes below are not invented: they are the literal joins of committed corpus
 * frames (`delivery_summary_collapsed` 07-17 and 08-23, plus the field-log `$291.0360` class).
 */
class GlyphCurrencyTransformTest {

    private fun parse(text: String?): Any? = TransformRegistry.apply("parseGlyphCurrency", text)

    // =========================================================================
    // Settled wheels — the values the parse must recover
    // =========================================================================

    @Test
    fun `a settled wheel with a leading label parses`() {
        // The 08-23 delivery_summary_expanded receipt, verbatim.
        assertEquals(16.70, parse("This dash so far\$16.70"))
    }

    @Test
    fun `a settled wheel with a TRAILING label parses`() {
        // The on-dash `earnings_pill` renders its label AFTER the digits, which is why the glyph
        // filter has to be two-sided rather than a leading-label strip.
        assertEquals(3.10, parse("\$3.10This dash"))
    }

    @Test
    fun `spacer text between the glyphs is stripped`() {
        // A space-separated wheel is exactly where `parseCurrency` fails WRONG: it splits on
        // space and takes the first token, so this same input reads $1.00 there.
        assertEquals(16.70, parse("\$ 1 6 . 7 0"))
        assertEquals(1.00, TransformRegistry.apply("parseCurrency", "\$ 1 6 . 7 0"))
    }

    @Test
    fun `a comma thousands group parses`() {
        assertEquals(1234.56, parse("This week\$1,234.56"))
    }

    @Test
    fun `the shape's boundaries are respected`() {
        assertEquals(0.00, parse("\$0.00"))
        assertEquals(9999.99, parse("\$9999.99"))
        assertEquals(9999.99, parse("\$9,999.99"))
    }

    @Test
    fun `the ONE currency shape rejects a leading-zero integer`() {
        // #1029 round 3: the old `\d{1,4}` accepted `$016.70`, and the fielded mid-spin
        // `$016.603` is exactly one settled digit away from it. No real figure is written that
        // way, so accepting it only widens the window for a spin frame to look settled.
        assertNull(parse("\$016.70"))
        assertNull(parse("This dash\$0016.70"))
    }

    @Test
    fun `the ONE currency shape rejects a malformed thousands group`() {
        // The old `\d{1,4}(?:,\d{3})?` matched this and folded it to 1234567.0.
        assertNull(parse("\$1234,567.00"))
        assertNull(parse("\$1,2345.00"))
        assertNull(parse("\$1,23.45"))
    }

    @Test
    fun `a five-figure integer is out of shape, not a windfall`() {
        assertNull(parse("\$12345.00"))
    }

    @Test
    fun `the comma arm honours the same four-digit ceiling`() {
        // #1052 E3: the arm read `[1-9]\d{0,2},\d{3}`, so it let SIX figures through the very
        // branch the shape's KDoc caps at four. A four-digit grouped figure still parses.
        assertEquals(1234.56, parse("\$1,234.56"))
        assertNull(parse("\$12,345.00"))
        assertNull(parse("\$123,456.00"))
    }

    // =========================================================================
    // #1052 E1 — unreadable input is REJECTED, not silently repaired
    // =========================================================================

    @Test
    fun `a non-ASCII digit rejects the whole read rather than being deleted`() {
        // Step 1 is a KEEP filter, so an unrecognised character simply vanishes and the remainder
        // can still full-match — turning a figure we cannot read into a confident wrong one. Here
        // the Arabic-Indic 2 sat INSIDE the figure, so the strip read $16.70 off a $126.70 wheel.
        assertNull(parse("This dash\$1\u06626.70"))
        assertNull(parse("\$\u06616.70"))
        // Devanagari, to show the rule is the digit PROPERTY and not one enumerated script.
        assertNull(parse("\$\u096716.70"))
    }

    @Test
    fun `a sign rejects the whole read rather than being deleted`() {
        // A minus and an accounting paren both change the figure's VALUE, which no glyph filter
        // can express — stripping them reports the magnitude as a positive.
        assertNull(parse("-\$16.70"))
        assertNull(parse("\u2212\$16.70"))
        assertNull(parse("(\$16.70)"))
        assertNull(parse("This dash so far-\$16.70"))
    }

    @Test
    fun `the rule-side pattern is the same shape, anchored`() {
        // Both sides derive from CurrencyShape, so this is the pin's local half: what the
        // transform accepts, a rule's `nextSiblingMatchingRegex` accepts, and vice versa.
        val ruleShape = Regex(CurrencyShape.RULE_PATTERN)
        for (good in listOf("\$0.00", "\$16.70", "\$9999.99", "\$1,234.56")) {
            assertEquals(good, true, ruleShape.matches(good))
            assertNotNull(parse(good))
        }
        for (bad in listOf("\$016.70", "\$1234,567.00", "\$12345.00", "\$,.00", "\$1,2,3.45")) {
            assertEquals(bad, false, ruleShape.matches(bad))
            assertNull(parse(bad))
        }
    }

    // =========================================================================
    // Mid-spin garbage — the whole reason the transform exists
    // =========================================================================

    @Test
    fun `the fielded mid-spin joins all fail closed`() {
        // 08-23 delivery_summary_collapsed: the wheel caught mid-flight, 4s before its
        // expanded sibling settled at $16.70.
        assertNull(parse("This dash so far\$70103.030"))
        // 07-17 delivery_summary_collapsed, the frame #801's rule comment cites.
        assertNull(parse("This dash so far\$016.603"))
        // The doubled-digit class from the field log.
        assertNull(parse("\$291.0360"))
    }

    @Test
    fun `an unsettled shape is never coerced into a number`() {
        assertNull(parse("\$1670"))       // no decimal point yet
        assertNull(parse("\$16.7"))       // one fraction digit
        assertNull(parse("\$16.700"))     // three fraction digits
        assertNull(parse("16.70"))        // no currency mark
        assertNull(parse("\$"))           // the wheel has only rendered the mark
        assertNull(parse(""))
    }

    // =========================================================================
    // Fail-closed on an ambiguous container — a mis-aimed rule reads null
    // =========================================================================

    @Test
    fun `a label carrying its own digits poisons the join and yields null`() {
        // The stats row that sits beside the receipt wheel. Folding it in would splice
        // "1 out of 1" into the figure, so the full-match rejects the whole read.
        assertNull(parse("This dash so far\$16.70Total online time 39 minOffers accepted1 out of 1"))
        assertNull(parse("1 out of 1\$16.70"))
    }

    @Test
    fun `two figures in one container yield null, never a fusion`() {
        assertNull(parse("\$8.70\$1.00"))
    }

    @Test
    fun `input past the bound is rejected without inspection`() {
        val padded = "x".repeat(TransformRegistry.MAX_GLYPH_CURRENCY_INPUT) + "\$16.70"
        assertNull(parse(padded))
    }

    @Test
    fun `null input stays null`() {
        assertNull(parse(null))
    }

    // =========================================================================
    // Engine wiring
    // =========================================================================

    @Test
    fun `the transform name is compile-validated like every other`() {
        TransformRegistry.validateTransformName("parseGlyphCurrency")
    }
}
