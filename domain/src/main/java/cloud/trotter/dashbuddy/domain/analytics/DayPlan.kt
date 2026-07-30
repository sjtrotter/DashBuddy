package cloud.trotter.dashbuddy.domain.analytics

/**
 * "Today's plan" — one weekday row of the lifetime hour×day heatmap, prepared for the Home strip
 * (#977, redesign stage 4 of #969; brief §2).
 *
 * This is a **projection of the driver's own record**, never a forecast of what the platform will
 * offer: every figure on it is `Σ their own frozen net ÷ Σ their own online hours` for that
 * hour-of-week, exactly as [EarningsHeatmap] already computed it. The surface that renders it is
 * required to say so (brief §2's copy rule — `from your own <weekday>s, lifetime — not a guarantee`)
 * and to say *nothing* about a rate when [hasEnoughHistory] is false.
 *
 * Pure `:domain` value type: no Android, no clock — the caller passes the weekday and the current
 * hour in (the UI derives the hour from its ticker, Reactive UI rule 2), which is what makes the
 * passed-hour marking and the window pick exhaustively unit-testable.
 */
data class DayPlan(
    /** 0=Monday..6=Sunday — the same indexing [EarningsHeatmap.cell] uses. */
    val dayIndex: Int,
    /** Always 24 cells, hour 0..23 in order. */
    val cells: List<DayPlanCell>,
    /**
     * How many of the weekday's 24 hours carry enough of the driver's own recorded presence to have a
     * rate at all (i.e. cleared [EarningsHeatmap.minCoverageHours]). This is the **sample count** the
     * thin-data rule is measured on — see [DayPlanner.MIN_SAMPLED_HOURS] for why it is hours and not
     * dashes.
     */
    val sampledHours: Int,
    /** True once [sampledHours] clears [DayPlanner.MIN_SAMPLED_HOURS]; false ⇒ never quote a rate. */
    val hasEnoughHistory: Boolean,
    /**
     * Tonight's best still-available window, or `null` when the weekday is too thin
     * ([hasEnoughHistory] false) or nothing contiguous qualifies in the hours that are left. Null is
     * a first-class answer here: the surface says so rather than inventing a window (§9 — thin data
     * is stated, never smoothed over).
     */
    val bestWindow: DayPlanWindow?,
)

/**
 * One hour of the [DayPlan] strip.
 *
 * [dollarsPerHour] is the heatmap cell's own masked rate — `null` means "not enough of your time
 * recorded in this hour to say", which the strip must render distinctly from a real `0.0`
 * ("worked, kept nothing"), exactly as the Patterns heatmap does.
 */
data class DayPlanCell(
    val hour: Int,
    val dollarsPerHour: Double?,
    /** True once the wall clock has moved past this hour — the strip dims it. */
    val passed: Boolean,
    /** True for the hours [DayPlan.bestWindow] covers — the strip outlines them. */
    val inBestWindow: Boolean,
)

/**
 * A contiguous run of qualifying hours, `[startHour, endHourExclusive)`, and the driver's own realized
 * rate across it.
 *
 * [dollarsPerHour] is **coverage-weighted** (`Σ net ÷ Σ coverage hours` over the run's cells), not a
 * mean of the per-cell rates: an hour the driver was online for ten minutes must not carry the same
 * weight in the window's headline figure as one they worked in full. It is the same division the
 * heatmap performs per cell, applied to the run — so the number on screen stays traceable to the
 * frozen record (brief §8's "every number must be traceable" rule, applied early).
 */
data class DayPlanWindow(
    val startHour: Int,
    val endHourExclusive: Int,
    val dollarsPerHour: Double,
) {
    val lengthHours: Int get() = endHourExclusive - startHour
}

/**
 * Derives [DayPlan] from the lifetime [EarningsHeatmap] (#977). Pure and total — no clock, no zone,
 * no Android; every input is an explicit parameter.
 *
 * **Thin-data rule.** The brief asks for "fewer than ~5 samples for that weekday ⇒ say so instead of
 * showing a rate". The heatmap is the only aggregate available, and it exposes *coverage hours per
 * cell* — the session spans were unioned and apportioned across cells before it was built, so the
 * dash COUNT is structurally gone by then and cannot be recovered read-side. The honest reading of
 * "sample" against what actually exists is therefore **an hour-of-week bucket carrying enough of the
 * driver's own presence to have a rate at all** (`dollarsPerHour != null`, i.e. it cleared the
 * heatmap's own [EarningsHeatmapCalculator.DEFAULT_MIN_COVERAGE_HOURS] floor). [MIN_SAMPLED_HOURS] of
 * those are required before ANY rate is quoted for the weekday. Deliberately stated in the surface's
 * copy as hours, never as dashes: five lit hours can be one long Monday, and the driver must be able
 * to tell that from five separate ones.
 */
object DayPlanner {

    /**
     * The weekday needs this many rate-bearing hours on record before the strip quotes a rate.
     * Below it, the surface states the count instead (brief §2's copy rule).
     */
    const val MIN_SAMPLED_HOURS = 5

    /**
     * A window shorter than this is an anecdote, not a plan — the same ≥2h floor brief §8's planner
     * uses, applied here so Home and the future Weekly Plan can't disagree about what a window is.
     */
    const val MIN_WINDOW_HOURS = 2

    /**
     * …and one longer than this stops being a "best bet": a run of ten qualifying hours describes the
     * whole day, so the pick is the best sub-run within it rather than the run itself.
     */
    const val MAX_WINDOW_HOURS = 4

    /**
     * Build today's plan from [heatmap] for weekday [dayIndex] (0=Monday), with the wall clock inside
     * hour [currentHour] (0..23 — the hour the driver is *in*, which counts as still available).
     *
     * Out-of-range inputs are coerced rather than thrown: this feeds a glance surface off a ticking
     * clock, and a crash would be a strictly worse answer than a clamped one.
     */
    fun plan(heatmap: EarningsHeatmap, dayIndex: Int, currentHour: Int): DayPlan {
        val day = dayIndex.coerceIn(0, EarningsHeatmap.DAYS - 1)
        val hourNow = currentHour.coerceIn(0, EarningsHeatmap.HOURS - 1)
        val row = List(EarningsHeatmap.HOURS) { heatmap.cell(day, it) }

        val sampledHours = row.count { it.dollarsPerHour != null }
        val hasEnoughHistory = sampledHours >= MIN_SAMPLED_HOURS
        // Fail-closed: a weekday the driver has barely worked yields no window at all, so the strip
        // can never outline hours it has no standing to recommend.
        val best = if (hasEnoughHistory) bestWindow(row, hourNow) else null

        return DayPlan(
            dayIndex = day,
            cells = row.map { cell ->
                DayPlanCell(
                    hour = cell.hour,
                    dollarsPerHour = cell.dollarsPerHour,
                    passed = cell.hour < hourNow,
                    inBestWindow = best != null && cell.hour >= best.startHour && cell.hour < best.endHourExclusive,
                )
            },
            sampledHours = sampledHours,
            hasEnoughHistory = hasEnoughHistory,
            bestWindow = best,
        )
    }

    /**
     * The highest-rate contiguous run of [MIN_WINDOW_HOURS]..[MAX_WINDOW_HOURS] qualifying hours that
     * starts at or after [currentHour].
     *
     * A cell **qualifies** only when it has a rate (cleared coverage) AND that rate is positive: a
     * masked hour is unknown, and a `≤ $0` hour is a measured bad hour — neither belongs in a "best
     * bet", and both end the run rather than being averaged into it.
     *
     * Ties keep the EARLIEST (and, within a start, the SHORTEST) window: the comparison is strict and
     * both loops ascend, so the pick is deterministic — a glance surface must not reshuffle its own
     * recommendation between two recompositions of identical data.
     */
    private fun bestWindow(row: List<EarningsHeatmapCell>, currentHour: Int): DayPlanWindow? {
        var best: DayPlanWindow? = null
        for (start in currentHour..(EarningsHeatmap.HOURS - MIN_WINDOW_HOURS)) {
            var net = 0.0
            var coverage = 0.0
            for (length in 1..MAX_WINDOW_HOURS) {
                val hour = start + length - 1
                if (hour >= EarningsHeatmap.HOURS) break
                val cell = row[hour]
                val rate = cell.dollarsPerHour ?: break
                if (rate <= 0.0) break
                net += cell.netDollars
                coverage += cell.coverageHours
                if (length < MIN_WINDOW_HOURS || coverage <= 0.0) continue
                val candidate = DayPlanWindow(start, start + length, net / coverage)
                if (best == null || candidate.dollarsPerHour > best.dollarsPerHour) best = candidate
            }
        }
        return best
    }
}
