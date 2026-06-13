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

    /** "$7.50" — standard money. */
    fun money(amount: Double): String = String.format(locale, "$%.2f", amount)

    /** "$23" — whole-dollar money (heroes, $/hr). */
    fun money0(amount: Double): String = String.format(locale, "$%.0f", amount)

    /** "$0.165" — per-mile cost precision. */
    fun money3(amount: Double): String = String.format(locale, "$%.3f", amount)

    /** "4.2" — bare decimal with [digits] places (callers add units). */
    fun decimal(value: Double, digits: Int = 1): String =
        String.format(locale, "%.${digits}f", value)

    /** "12,500" — grouped integer. */
    fun commaInt(value: Int): String = String.format(locale, "%,d", value)
}
