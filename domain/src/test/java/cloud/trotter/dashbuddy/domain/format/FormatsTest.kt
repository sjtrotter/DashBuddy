package cloud.trotter.dashbuddy.domain.format

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * #358 locale policy: display formatting follows the DEVICE locale —
 * explicitly, not as an invisible side effect of bare `.format`.
 */
class FormatsTest {

    private lateinit var original: Locale

    @Before
    fun saveLocale() {
        original = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `US locale formats with dot decimals and comma grouping`() {
        Locale.setDefault(Locale.US)
        assertEquals("$7.50", Formats.money(7.5))
        assertEquals("$23", Formats.money0(23.4))
        assertEquals("$0.165", Formats.money3(0.1649))
        assertEquals("4.2", Formats.decimal(4.23))
        assertEquals("12,500", Formats.commaInt(12_500))
    }

    @Test
    fun `comma-decimal locale renders ITS convention - deliberately`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("$7,50", Formats.money(7.5))
        assertEquals("12.500", Formats.commaInt(12_500))
    }

    /**
     * #1034: a negative renders sign-BEFORE-symbol. `String.format("$%.2f", …)` treats the `$` as a
     * literal prefix and let the numeric conversion place its own sign, so the Money card headline
     * shipped `$-65.94 went to the car.` on the 08-17→08-23 window.
     */
    @Test
    fun `money puts the sign before the currency symbol`() {
        Locale.setDefault(Locale.US)
        assertEquals("-$65.94", Formats.money(-65.94))
        assertEquals("$0.00", Formats.money(0.0))
        assertEquals("$65.94", Formats.money(65.94))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("-$65,94", Formats.money(-65.94))
    }

    /**
     * All three arities share ONE renderer, so the whole-dollar `$/hr` hero and the per-mile figure
     * carry the sign the same way. `money0` is the one that shipped a negative in practice — a bad
     * offer's hourly hero — and its old form rendered `$-12`.
     */
    @Test
    fun `money0 and money3 place the sign the same way`() {
        Locale.setDefault(Locale.US)
        assertEquals("-$12", Formats.money0(-12.0))
        assertEquals("$23", Formats.money0(23.4))
        assertEquals("-$0.165", Formats.money3(-0.1649))
        assertEquals("$0.165", Formats.money3(0.1649))
    }

    /**
     * The sign MOVES; the digits do not. Only `"%.Nf"` decides rounding, exactly as before — a
     * negative rounds HALF_UP on its magnitude, so `-0.005` → `-$0.01`. Pinned, not chosen: changing
     * it would be a second formatting policy.
     */
    @Test
    fun `money pins the rounding String format already gave`() {
        Locale.setDefault(Locale.US)
        assertEquals("-$0.01", Formats.money(-0.005))
        assertEquals("$0.01", Formats.money(0.005))
    }

    /**
     * A SUB-CENT magnitude that renders as zero drops its sign. The old `$-0.00` was a fabricated
     * sign in the old glyph order; moving it to the front would only relocate the fabrication. The
     * negative-zero `Double` — which reaches the formatter from any subtraction that cancels — is the
     * same case.
     */
    @Test
    fun `a sub-cent magnitude renders unsigned`() {
        Locale.setDefault(Locale.US)
        assertEquals("$0.00", Formats.money(-0.004))
        assertEquals("$0.00", Formats.money(-0.0))
        assertEquals("$0.00", Formats.money(0.004))
        assertEquals("$0", Formats.money0(-0.004))
        assertEquals("$0.000", Formats.money3(-0.0004))
    }

    /**
     * A **real** negative that merely rounds away at a coarse arity KEEPS its sign. The bubble paints
     * that `$/hr` hero in the bad tone beneath a "drop it" verdict, so an unsigned `$0/hr` would
     * contradict the banner beside it — and the suppression floor is a money floor, not a per-arity
     * one, so it can never widen with precision.
     */
    @Test
    fun `a real negative keeps its sign even when the arity rounds it away`() {
        Locale.setDefault(Locale.US)
        assertEquals("-$0", Formats.money0(-0.4))
        assertEquals("-$1", Formats.money0(-0.6))
        assertEquals("-$0.004", Formats.money3(-0.004))
    }

    /**
     * Non-finite values fall out of the same rule rather than needing a branch: `Infinity` never
     * equals a formatted zero (so the sign survives), and `NaN` is not `< 0.0` (so none is invented).
     * Neither should ever reach a money surface — a `0.0` denominator is guarded at the read sites —
     * but a formatter that inverts a sign under one is worse than one that doesn't.
     */
    @Test
    fun `non-finite values keep an honest sign`() {
        Locale.setDefault(Locale.US)
        assertEquals("-\$Infinity", Formats.money(Double.NEGATIVE_INFINITY))
        assertEquals("\$Infinity", Formats.money(Double.POSITIVE_INFINITY))
        assertEquals("\$NaN", Formats.money(Double.NaN))
    }

    /**
     * The zero test compares against `0.0` formatted through the SAME pattern and locale, so it holds
     * on a locale whose digits are not Latin — where a digit scan against ASCII `'0'` would see "no
     * digits", call a real figure zero, and drop its sign.
     *
     * `ar-EG` is chosen deliberately: bare `ar` and `ar-DZ` format with Latin digits on the JDK, so
     * they would pass this test without exercising anything (`TransformHardeningTest` is the in-repo
     * precedent for the same choice).
     */
    @Test
    fun `the zero test holds on a non-Latin-digit locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val negative = Formats.money(-65.94)
        assertTrue("expected a leading '-', got $negative", negative.startsWith("-$"))
        assertTrue("expected Arabic-Indic digits, got $negative", negative.any { it in '٠'..'٩' })
        assertFalse("a sub-cent magnitude must not carry a sign", Formats.money(-0.004).startsWith("-"))
    }

    /**
     * #942: [Formats.percent] replaced four hand-rolled `(x * 100).roundToInt()` sites, so its
     * whole-number output must be byte-identical to what those rendered.
     */
    @Test
    fun `percent matches the hand-rolled roundToInt shape it replaced`() {
        Locale.setDefault(Locale.US)
        assertEquals("0%", Formats.percent(0.0))
        assertEquals("100%", Formats.percent(1.0))
        assertEquals("67%", Formats.percent(2.0 / 3.0))
        assertEquals("33%", Formats.percent(1.0 / 3.0))
        // .5 rounds up, exactly as roundToInt() does for a non-negative value.
        assertEquals("13%", Formats.percent(0.125))
        assertEquals("74%", Formats.percent(0.7351))
    }

    @Test
    fun `percent takes the fraction, never the pre-multiplied value`() {
        Locale.setDefault(Locale.US)
        assertEquals("8%", Formats.percent(0.08))
        assertEquals("800%", Formats.percent(8.0))
    }

    @Test
    fun `percent honors a decimals request and the device locale`() {
        Locale.setDefault(Locale.US)
        assertEquals("66.7%", Formats.percent(2.0 / 3.0, decimals = 1))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("66,7%", Formats.percent(2.0 / 3.0, decimals = 1))
    }
}
