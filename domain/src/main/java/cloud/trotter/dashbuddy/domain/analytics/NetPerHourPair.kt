package cloud.trotter.dashbuddy.domain.analytics

/**
 * The window's net **per hour, both ways** (#983 / brief §6 `4c`) — the pair
 * `docs/design/running-hourly-rate.md` §2a defines and the app has never surfaced.
 *
 * ## The two denominators (the doc is the SSOT)
 *
 * The doc names them **active $/hr** and **scheduled $/hr**; the redesign brief §6 asks for them under
 * the driver-facing labels **while working** and **whole shift**. Same two denominators, two
 * vocabularies — the doc's definitions govern what is computed here, the brief's words govern what the
 * card says:
 *
 * - **[whileWorking] (the doc's *active*)** — net ÷ time spent *engaged* on deliveries: driving to and
 *   from, waiting at the store, standing at the door. "The efficiency of the work itself."
 * - **[wholeShift] (the doc's *scheduled*)** — net ÷ total clock time online, **including the dry gaps
 *   between offers**. "What actually lands in your pocket per hour of your life spent dashing."
 *
 * The doc's guidance (§7) is that a driver needs both: the first says whether the work is worth doing,
 * the second whether the market is worth being in.
 *
 * ## How each denominator is measured
 *
 * [wholeShift] is the easy one: [TimeEconomics.onlineMillis], Σ session durations — the doc's §5
 * "Layer 0" number, which contains **zero estimates**.
 *
 * [whileWorking] is `Σ per-delivery partition deltas − Σ §7.8 gaps`. The partition delta already spans
 * accept-to-completion work, but it *also* swallows the dry wait that preceded the job (the delta runs
 * from the previous drop), which is exactly the term the doc excludes from active time — so the gap
 * fold is what finally makes an active denominator measurable rather than approximate. That is why
 * §7.8 is listed in the brief as feeding §6.
 *
 * **Known, one-sided residuals** (stated, per §9; each pushes [whileWorking] DOWN, never up):
 *  - the wait before a dash's *first* offer has no preceding completion, so §7.8 cannot see it and it
 *    stays inside the working denominator;
 *  - a gap whose following job never completed is subtracted from a denominator that never contained
 *    it — the subtraction is floored at 0 and the residual is at most that one gap.
 * So [whileWorking] is conservative by construction: the real active rate is at least this.
 *
 * ## What the numerator is
 *
 * [netProfit] is the window's **frozen** net (Σ per-delivery `netProfit` + unattributed pay + cash, as
 * assembled by the repository) — an economy edit never rewrites it, and nothing here re-costs it
 * (Principle 5). Both rates therefore share one numerator with the recap hero, so the pair can never
 * disagree with the headline above it.
 *
 * Null rather than zero wherever the denominator was never measured (#936 discipline): a `0.0/hr` on a
 * window with no measured time would read as "you earned nothing", which is a different claim.
 */
data class NetPerHourPair(
    /** The window's frozen net — the shared numerator. */
    val netProfit: Double,
    /** Engaged time: Σ per-delivery deltas minus the measured gaps (≥ 0). */
    val workingMillis: Long,
    /** Σ session durations — every minute online. */
    val onlineMillis: Long,
    /** How many measured gaps were removed from the working denominator — the coverage figure. */
    val gapsSubtracted: Int,
    /** True when the window measured no per-delivery time at all (so [whileWorking] is null). */
    val workingTimeUnmeasured: Boolean,
) {
    /** Net ÷ engaged hours (the doc's *active $/hr*); null when no engaged time was measured. */
    val whileWorking: Double? get() = rate(workingMillis)

    /** Net ÷ online hours (the doc's *scheduled $/hr*); null when no online time was measured. */
    val wholeShift: Double? get() = rate(onlineMillis)

    /** True when both rates exist — the card renders the pair, otherwise it states what is missing. */
    val hasPair: Boolean get() = whileWorking != null && wholeShift != null

    /**
     * What [millis] of time is worth at the **while-working** rate — the brief's "leak stated in
     * dollars" (`waiting cost you ~$X`).
     *
     * Deliberately priced at the working rate, not the whole-shift one: the question the leak answers
     * is "what would that time have been worth had it been spent working", and the whole-shift rate
     * already has this very idleness baked into its denominator (pricing waiting at a rate that
     * assumes waiting is circular). It is a factual restatement of measured time at a measured rate —
     * never a promise that the time *could* have been filled, which depends on a market we do not and
     * will not see (doc §4). Null when the working rate was never measured.
     */
    fun valueOf(millis: Long): Double? =
        whileWorking?.let { rate -> if (millis <= 0L) 0.0 else rate * (millis.toDouble() / HOUR_MILLIS) }

    private fun rate(denominatorMillis: Long): Double? =
        if (denominatorMillis <= 0L) null else netProfit / (denominatorMillis.toDouble() / HOUR_MILLIS)

    companion object {
        private const val HOUR_MILLIS = 3_600_000.0

        val EMPTY = NetPerHourPair(
            netProfit = 0.0,
            workingMillis = 0L,
            onlineMillis = 0L,
            gapsSubtracted = 0,
            workingTimeUnmeasured = true,
        )

        /**
         * Build the pair from the window's frozen [netProfit], its measured [time], and its measured
         * [gaps] — all three read off the same window bounds, so the numerator and both denominators
         * describe one population.
         */
        fun of(netProfit: Double, time: TimeEconomics, gaps: GapStats): NetPerHourPair {
            val deliveryMillis = time.deliveryMillis
            return NetPerHourPair(
                netProfit = netProfit,
                // Floored at 0: over-subtraction is possible only via the documented one-sided
                // residual above, and a negative denominator must never become a rate.
                workingMillis = ((deliveryMillis ?: 0L) - gaps.totalMillis).coerceAtLeast(0L),
                onlineMillis = time.onlineMillis,
                gapsSubtracted = gaps.count,
                workingTimeUnmeasured = deliveryMillis == null,
            )
        }
    }
}
