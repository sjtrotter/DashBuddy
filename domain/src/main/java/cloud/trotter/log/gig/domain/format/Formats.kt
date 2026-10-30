package cloud.trotter.log.gig.domain.format

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

data class MoneyParts(val sign: String, val symbol: String, val whole: String, val fraction: String)

/**
 * Locale policy for every formatted number in gig.log (#358, #456):
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
    /** Truncate the displayed cents, rather than the unrounded input. */
    fun wholeDollars(value: Double): Long = cents(value) / 100


    private val locale: Locale get() = Locale.getDefault()

    /**
     * Half a cent — below this a negative magnitude is not money, so [signedMoney] drops its sign
     * rather than rendering a minus in front of a zero. It is a MONEY floor, not a per-arity one:
     * a coarse arity that rounds a real negative away keeps the sign (`money0(-0.4)` → `-$0`).
     */
    private const val SUB_CENT = 0.005

    /** "$7.50" — standard money. A negative renders `-$65.94` (see [signedMoney]). */
    fun money(amount: Double): String = signedMoney(amount, decimals = 2)

    /** Cents below a dollar, using the same cent rounding as money. */
    fun moneyOrCents(amount: Double): String {
        val cents = cents(amount)
        return if (abs(cents) < 100) "${cents}¢" else money(amount)
    }

    /** Split the canonical money text; fraction includes the locale decimal separator. */
    fun moneyParts(amount: Double): MoneyParts {
        val text = money(amount)
        val sign = if (text.startsWith("-")) "-" else ""
        val magnitude = text.removePrefix(sign).removePrefix("$")
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val split = magnitude.indexOf(separator)
        return MoneyParts(sign, "$", if (split < 0) magnitude else magnitude.substring(0, split),
            if (split < 0) "" else magnitude.substring(split))
    }

    /** "$23" — whole-dollar money (heroes, $/hr). A negative renders `-$23` (see [signedMoney]). */
    fun money0(amount: Double): String = signedMoney(amount, decimals = 0)

    /** "$0.165" — per-mile cost precision. A negative renders `-$0.165` (see [signedMoney]). */
    fun money3(amount: Double): String = signedMoney(amount, decimals = 3)

    /**
     * Whole signed cents, rounded exactly as [money] renders them: through the same `%.2f` on the
     * magnitude, so a figure that money shows as `-$1.01` is `-101` here, never `-100`. Arithmetic
     * on money (a remainder to a goal, a split) goes through this, never through `* 100` on a double.
     */
    fun cents(amount: Double): Long {
        val magnitude = String.format(Locale.ROOT, "%.2f", abs(amount)).replace(".", "").toLong()
        return if (amount < 0.0) -magnitude else magnitude
    }

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
     * **The sign is dropped only for a genuinely sub-cent magnitude that also renders as zero.**
     * `money(-0.004)` is `$0.00`, `money0(-0.004)` is `$0`, `money3(-0.0004)` is `$0.000` — the old
     * `$-0.00` was a fabricated sign in the old glyph order, and moving it to the front would only
     * relocate the fabrication. But a **real** negative that merely rounds away at a coarse arity
     * KEEPS its sign: `money0(-0.4)` is `-$0` and `money0(-0.6)` is `-$1`, because the bubble renders
     * that hero in the bad tone under a "drop it" verdict and an unsigned `$0/hr` there would
     * contradict the banner beside it. [SUB_CENT] is the money floor, so the drop can never widen
     * with precision — `money3(-0.004)` stays `-$0.004`.
     *
     * "Renders as zero" is decided by comparing against `0.0` formatted through the SAME pattern and
     * the SAME locale snapshot, so the two strings are comparable by construction — no digit scan,
     * and nothing to get wrong on a non-Latin digit locale. A non-finite value falls out correctly
     * for free: `Infinity` never equals a formatted zero, so `-Infinity` → `-$Infinity`, and `NaN`
     * fails `amount < 0.0` → `$NaN`.
     */
    private fun signedMoney(amount: Double, decimals: Int): String {
        val loc = locale
        val pattern = "%.${decimals}f"
        val magnitude = String.format(loc, pattern, abs(amount))
        val rendersZero = magnitude == String.format(loc, pattern, 0.0)
        val dropSign = rendersZero && abs(amount) < SUB_CENT
        return if (amount < 0.0 && !dropSign) "-$$magnitude" else "$$magnitude"
    }

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
