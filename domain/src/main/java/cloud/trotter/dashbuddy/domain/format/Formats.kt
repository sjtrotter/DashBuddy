package cloud.trotter.dashbuddy.domain.format

import java.util.Locale
import kotlin.math.abs

/**
 * Locale policy for every formatted number in DashBuddy (#358, #456):
 *
 *  - **Display strings** (anything a human reads: money, distances, rates,
 *    counts) format through THIS object, which pins `Locale.getDefault()`
 *    explicitly. Bare `"%.2f".format(x)` is banned — it picks up the default
 *    locale *implicitly*, which is the bug pattern this object exists to kill
 *    (it made the policy invisible and un-greppable).
 *  - **Machine strings** (hashes, file names, wire values, anything parsed
 *    back — e.g. editable text fields that round-trip `toDoubleOrNull`) pin
 *    `Locale.ROOT`/`Locale.US` at their own call site.
 *
 * Lives in `:domain` (pure Kotlin, no Android) so BOTH the UI (`:app`) and the
 * state layer (`:core:state`) route through the one definition. EffectMap used
 * to carry a private `formatCurrency` that omitted the `$` — the "Saved: $X"
 * bubble rendered `Saved: 5.50` (#456) — because `:core:state` can't reach the
 * old designsystem `DashFormats`. Pulling the formatter down to `:domain`
 * removes that divergence (and the platform-flavoured "Dash" name). Formatting
 * is platform-neutral; the name is too.
 *
 * Acceptance grep: `.format(` without `Locale` on the same line should not
 * appear in ui/ or rendered-copy code — everything routes here.
 */
object Formats {

    private val locale: Locale get() = Locale.getDefault()

    /** "$7.50" — standard money. A negative renders `-$65.94` (see [signedMoney]). */
    fun money(amount: Double): String = signedMoney(amount, decimals = 2)

    /** "$23" — whole-dollar money (heroes, $/hr). A negative renders `-$23` (see [signedMoney]). */
    fun money0(amount: Double): String = signedMoney(amount, decimals = 0)

    /** "$0.165" — per-mile cost precision. A negative renders `-$0.165` (see [signedMoney]). */
    fun money3(amount: Double): String = signedMoney(amount, decimals = 3)

    /**
     * The one money renderer (#1034): `$` first, then the magnitude, with a `-` **before** the
     * symbol when the value is negative.
     *
     * `String.format(locale, "$%.2f", …)` treats the `$` as a literal PREFIX and lets the numeric
     * conversion place its own sign, so a negative used to render `$-65.94` — which reached the Money
     * card headline verbatim (`"$0.00 came in. $-65.94 went to the car."` on the 08-17→08-23 window).
     * Negatives are legitimate across this whole family, not an anomaly: `MoneyWentCard`'s derived
     * car cost can go negative (#662-F1), #1024 deliberately renders a losing window's kept clause,
     * and a bad offer's `$/hr` hero ([money0]) is negative whenever costs outrun the pay. So all
     * three arities share this shape rather than diverging by precision.
     *
     * The digits are produced by the **same** `"%.Nf"` conversion as before — `"$%.Nf"` is exactly
     * that conversion with a literal `$` glued on — applied to the magnitude, which is exact for a
     * `Double`. Rounding, the locale decimal separator and the absence of grouping are therefore
     * byte-identical to the old output: `-0.005` → `-$0.01`, HALF_UP on the magnitude.
     *
     * **A magnitude that rounds to zero renders NO sign.** `-0.004` is `$0.00`, not `-$0.00`: the old
     * `$-0.00` was a fabricated sign in the old glyph order, and moving it to the front would only
     * relocate the fabrication. The test is whether the *rendered* text carries a non-zero digit, so
     * it agrees with what the reader sees at every precision, and it is written against Unicode
     * decimal values rather than ASCII `'0'` so a non-Latin digit locale can never be read as a
     * silent zero (which would drop a real sign).
     */
    private fun signedMoney(amount: Double, decimals: Int): String {
        val magnitude = String.format(locale, "%.${decimals}f", abs(amount))
        return if (amount < 0.0 && !rendersZero(magnitude)) "-$$magnitude" else "$$magnitude"
    }

    /** True when [text] holds no non-zero digit, i.e. the rounded magnitude reads as zero. */
    private fun rendersZero(text: String): Boolean =
        text.none { it.isDigit() && Character.digit(it, 10) != 0 }

    /** "4.2" — bare decimal with [digits] places (callers add units). */
    fun decimal(value: Double, digits: Int = 1): String =
        String.format(locale, "%.${digits}f", value)

    /** "12,500" — grouped integer. */
    fun commaInt(value: Int): String = String.format(locale, "%,d", value)

    /**
     * "67%" — a 0..1 [fraction] rendered as a percentage with [decimals] places
     * (#942). Callers pass the fraction, NOT the pre-multiplied value: the `* 100`
     * belongs to the formatter, and hand-rolling it at the call site is how the
     * bubble's acceptance rate (integer-truncated) and the Decisions tab's
     * (rounded) came to disagree by a point on the same metric.
     */
    fun percent(fraction: Double, decimals: Int = 0): String =
        String.format(locale, "%.${decimals}f%%", fraction * 100.0)
}
