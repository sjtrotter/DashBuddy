package cloud.trotter.dashbuddy.domain.format

import java.util.Locale

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

    /**
     * "$7.50" — standard money. A negative renders **`-$65.94`**, sign before the symbol (#1034).
     *
     * `String.format(locale, "$%.2f", …)` treats the `$` as a literal PREFIX and lets the numeric
     * conversion place its own sign, so a negative used to render `$-65.94` — which reached the Money
     * card headline verbatim (`"$0.00 came in. $-65.94 went to the car."` on the 08-17→08-23 window).
     * Negatives are legitimate here, not an anomaly: `MoneyWentCard`'s derived car cost can go
     * negative (#662-F1) and #1024 deliberately renders a losing window's kept clause, so the glyph
     * order has to be right rather than merely rare.
     *
     * The digits are produced by the **same** `"%.2f"` conversion as before and only the sign moves,
     * so rounding, the locale decimal separator, and the absence of grouping are byte-identical to
     * the old output (`-0.005` → `-$0.01`, HALF_UP on the magnitude; `-0.004` → `-$0.00`, the signed
     * zero the old `$-0.00` also rendered). A locale whose minus is not ASCII `-` falls through the
     * `startsWith` check and keeps the old shape — a fail-safe, never a wrong sign.
     */
    fun money(amount: Double): String {
        val formatted = String.format(locale, "%.2f", amount)
        return if (formatted.startsWith('-')) "-$" + formatted.substring(1) else "$$formatted"
    }

    /**
     * "$23" — whole-dollar money (heroes, $/hr).
     *
     * **Deliberately NOT sign-moved with [money] (#1034 scope).** One caller seeds an editable text
     * field with `money0(x).removePrefix("$")`, which a leading `-` would silently defeat, so moving
     * the sign here is a caller change rather than a formatter change and is left for its own pass.
     */
    fun money0(amount: Double): String = String.format(locale, "$%.0f", amount)

    /**
     * "$0.165" — per-mile cost precision. Also not sign-moved: every call site is a per-mile cost
     * constant or an IRS rate, none of which is negative (#1034 scope).
     */
    fun money3(amount: Double): String = String.format(locale, "$%.3f", amount)

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
