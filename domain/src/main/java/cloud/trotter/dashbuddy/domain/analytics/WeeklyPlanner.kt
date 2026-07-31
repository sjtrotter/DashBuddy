package cloud.trotter.dashbuddy.domain.analytics

/**
 * Builds a [WeeklyPlan] from the driver's own record (#981 / brief §8's algorithm, verbatim):
 *
 *  1. rank weekday×hour cells by the **median** realized net $/hr of the driver's own history;
 *  2. require a minimum sample count per cell ([MIN_SAMPLE_DAYS] separate days);
 *  3. merge adjacent qualifying cells into contiguous windows of at least [MIN_WINDOW_HOURS];
 *  4. greedily take windows until the target is met;
 *  5. project each window as `median rate × hours`;
 *  6. the random-placement baseline is `overall median rate × hours`.
 *
 * "Every number on the screen must be traceable to that" is the governing constraint, so this object
 * computes NOTHING it cannot point at: no smoothing, no extrapolation, no default rate for an hour with
 * no record. Cells below the sample threshold are never picked, and the weekday they sit on is returned
 * in [WeeklyPlan.notPicked] with the reason ([UnpickedReason]) rather than silently vanishing.
 *
 * Pure and deterministic (Principle 1). No clock, no zone, no Android: the caller passes
 * [HourOfWeekSamples], which already carries the driver's record bucketed by weekday and hour. Every
 * ordering is total — rate descending, then earliest weekday, earliest start, shortest window — so the
 * same record and target always produce the same plan, byte for byte. A planner that reshuffled its own
 * recommendation between two recompositions of identical data would be worse than no planner.
 */
object WeeklyPlanner {

    /** A window shorter than this is an anecdote, not a plan — the same floor [DayPlanner] uses. */
    const val MIN_WINDOW_HOURS = DayPlanner.MIN_WINDOW_HOURS

    /**
     * …and one longer than this stops being a *window* and becomes "work all day". Capping it also lets
     * a 12h target spread across several weekdays instead of collapsing onto the one best run.
     */
    const val MAX_WINDOW_HOURS = DayPlanner.MAX_WINDOW_HOURS

    /**
     * The minimum number of **separate days** behind a median before the planner will place a window on
     * it (brief §8's "minimum sample count per cell").
     *
     * Three, deliberately: a median over two points is the mean of two points and carries none of the
     * robustness the brief asks a median for, while demanding five would refuse to plan anything for a
     * driver with two months of history — the exact person this screen is for. The count is quoted to
     * the driver on every row (`over N Fridays`), so the threshold is a floor on what gets *placed*,
     * never a substitute for telling them how thin the evidence is.
     */
    const val MIN_SAMPLE_DAYS = 3

    /**
     * Hard cap on a plan's placed hours. Bounds the greedy loop for a `$` target (which otherwise
     * terminates only on running out of candidates) and refuses to write a plan no human can work.
     */
    const val MAX_PLAN_HOURS = 40

    /**
     * Plan [target] against [samples], replaying the driver's [edits] on top.
     *
     * Edits are **replayed**, never applied to a stored plan: the base plan is recomputed from the
     * record every time and each edit is re-applied in order, so a moved window re-derives its rate at
     * its new hours from the record (never carrying a number measured somewhere else), and a dropped
     * window's hours are re-filled from the next-best candidate that isn't in banned territory.
     */
    fun plan(
        samples: HourOfWeekSamples,
        target: PlanTarget,
        edits: List<PlanEdit> = emptyList(),
    ): WeeklyPlan {
        val candidates = candidates(samples)
        val banned = ArrayList<BannedRange>()
        var selected = fill(emptyList(), candidates, target, banned)

        for (edit in edits) {
            selected = when (edit) {
                is PlanEdit.Drop -> {
                    val victim = selected.firstOrNull { it.dayIndex == edit.dayIndex && it.startHour == edit.startHour }
                    if (victim == null) {
                        selected
                    } else {
                        banned += BannedRange(victim.dayIndex, victim.startHour, victim.endHourExclusive)
                        selected - victim
                    }
                }

                is PlanEdit.Shift -> {
                    val victim = selected.firstOrNull { it.dayIndex == edit.dayIndex && it.startHour == edit.startHour }
                    if (victim == null) {
                        selected
                    } else {
                        val moved = shifted(victim, edit.deltaHours, samples)
                        // The vacated hours are banned too: re-filling the very slot the driver just
                        // moved a window OUT of would read as the app arguing with them.
                        banned += BannedRange(victim.dayIndex, victim.startHour, victim.endHourExclusive)
                        selected - victim + moved
                    }
                }
            }
            selected = fill(selected, candidates, target, banned)
        }

        val ordered = selected.sortedWith(compareBy({ it.dayIndex }, { it.startHour }))
        return WeeklyPlan(
            target = target,
            windows = markBest(ordered),
            notPicked = unpicked(samples, candidates, ordered, banned),
            randomPlacementRate = samples.overallMedianRate,
            edits = edits,
        )
    }

    // ── Candidate generation ────────────────────────────────────────────────

    /** A weekday+hour range the record can stand behind, with the median it stands behind it with. */
    private data class BannedRange(val dayIndex: Int, val startHour: Int, val endHourExclusive: Int)

    /**
     * Every window the record supports: for each weekday, the hours whose own median clears
     * [MIN_SAMPLE_DAYS] samples and `> $0`, merged into contiguous runs, then every sub-range of a run
     * with length [MIN_WINDOW_HOURS]..[MAX_WINDOW_HOURS] whose *own* occurrences also clear the sample
     * floor.
     *
     * The window-level check is not redundant with the per-cell one: 5–9 pm can hold four well-sampled
     * cells while no single day ever covered half of the block (an hour here, an hour there, on
     * different weeks), and it is the whole block the plan is about to recommend. The coverage floor
     * scales with the range asked about, so the longer the window, the more of it the driver must
     * actually have worked before it can be placed.
     */
    private fun candidates(samples: HourOfWeekSamples): List<PlannedWindow> {
        val out = ArrayList<PlannedWindow>()
        for (day in 0 until EarningsHeatmap.DAYS) {
            val qualifies = BooleanArray(EarningsHeatmap.HOURS) { hour ->
                val rates = samples.occurrenceRates(day, hour, hour + 1)
                val median = HourOfWeekSamples.median(rates)
                rates.size >= MIN_SAMPLE_DAYS && median != null && median > 0.0
            }
            for (run in runsOf(qualifies)) {
                for (start in run.first..run.last) {
                    for (length in MIN_WINDOW_HOURS..MAX_WINDOW_HOURS) {
                        val end = start + length
                        if (end - 1 > run.last) break
                        val rates = samples.occurrenceRates(day, start, end)
                        val median = HourOfWeekSamples.median(rates)
                        if (rates.size >= MIN_SAMPLE_DAYS && median != null && median > 0.0) {
                            out += PlannedWindow(day, start, end, median, rates.size)
                        }
                    }
                }
            }
        }
        return out
    }

    /** Index ranges of consecutive `true` entries. */
    private fun runsOf(flags: BooleanArray): List<IntRange> {
        val runs = ArrayList<IntRange>()
        var start = -1
        for (i in flags.indices) {
            if (flags[i]) {
                if (start < 0) start = i
            } else if (start >= 0) {
                runs += start..(i - 1)
                start = -1
            }
        }
        if (start >= 0) runs += start..(flags.lastIndex)
        return runs
    }

    // ── Greedy fill ─────────────────────────────────────────────────────────

    /**
     * Take candidates in rate order until the target is met, never overlapping an already-placed or
     * banned range, never exceeding the remaining hour budget.
     *
     * Ties break to the **longest** window first, then the earliest weekday, then the earliest start —
     * a total order, which is what makes the output deterministic.
     *
     * Longest-first is load-bearing, not cosmetic: a run of four equally-good hours yields sub-ranges
     * that all share one median, and shortest-first would take 5–7 pm and then 7–9 pm and render brief
     * §8's *merged contiguous window* as two rows the driver has to mentally staple back together.
     * Preferring the longest is what actually performs the "merge adjacent qualifying cells" step.
     */
    private fun fill(
        selected: List<PlannedWindow>,
        candidates: List<PlannedWindow>,
        target: PlanTarget,
        banned: List<BannedRange>,
    ): List<PlannedWindow> {
        var current = selected
        while (true) {
            val placedHours = current.sumOf { it.lengthHours }
            val projected = current.sumOf { it.projectedKept }
            val remaining = when (target) {
                is PlanTarget.Hours -> target.hours - placedHours
                // A dollars target has no hour figure of its own, so the plan cap is its only budget.
                is PlanTarget.Dollars -> if (projected >= target.amount) 0 else MAX_PLAN_HOURS - placedHours
            }
            if (remaining < MIN_WINDOW_HOURS) return current

            val next = candidates
                .filter { c ->
                    c.lengthHours <= remaining &&
                        current.none { it.overlaps(c) } &&
                        banned.none { b -> b.dayIndex == c.dayIndex && b.startHour < c.endHourExclusive && c.startHour < b.endHourExclusive }
                }
                .sortedWith(
                    compareByDescending<PlannedWindow> { it.dollarsPerHour ?: 0.0 }
                        .thenByDescending { it.lengthHours }
                        .thenBy { it.dayIndex }
                        .thenBy { it.startHour },
                )
                .firstOrNull() ?: return current

            current = current + next
        }
    }

    // ── Edits ───────────────────────────────────────────────────────────────

    /**
     * Move [window] by [deltaHours], clamped inside the day, and **re-derive** its rate and sample count
     * from the record at the new hours.
     *
     * A window moved somewhere the driver has never worked keeps its place in the plan (it is their
     * plan) but gets a null rate: the row says there is no history for those hours and the projection
     * counts nothing for it, rather than carrying over a number measured three hours earlier.
     */
    private fun shifted(window: PlannedWindow, deltaHours: Int, samples: HourOfWeekSamples): PlannedWindow {
        val maxStart = EarningsHeatmap.HOURS - window.lengthHours
        val start = (window.startHour + deltaHours).coerceIn(0, maxStart)
        val end = start + window.lengthHours
        val rates = samples.occurrenceRates(window.dayIndex, start, end)
        return window.copy(
            startHour = start,
            endHourExclusive = end,
            dollarsPerHour = HourOfWeekSamples.median(rates),
            sampleCount = rates.size,
            moved = true,
        )
    }

    /** Flag the single highest-rate placed window (ties → earliest, the list is already ordered). */
    private fun markBest(windows: List<PlannedWindow>): List<PlannedWindow> {
        val best = windows.filter { it.dollarsPerHour != null }.maxByOrNull { it.dollarsPerHour!! } ?: return windows
        var flagged = false
        return windows.map {
            if (!flagged && it === best) {
                flagged = true
                it.copy(isBest = true)
            } else {
                it
            }
        }
    }

    // ── "NOT PICKED", with the reason ───────────────────────────────────────

    /**
     * One row per weekday holding no placed window, each carrying the measured reason. This is brief
     * §8's dimmed `NOT PICKED` list and §9's thin-data rule in the same object: a weekday that didn't
     * make the plan is stated, with what the record actually says about it.
     */
    private fun unpicked(
        samples: HourOfWeekSamples,
        candidates: List<PlannedWindow>,
        selected: List<PlannedWindow>,
        banned: List<BannedRange>,
    ): List<UnpickedDay> = (0 until EarningsHeatmap.DAYS)
        .filter { day -> selected.none { it.dayIndex == day } }
        .map { day ->
            val onRecord = samples.daysOnRecord(day)
            val dayCandidates = candidates.filter { it.dayIndex == day }
            val available = dayCandidates.filter { c ->
                banned.none { b -> b.dayIndex == day && b.startHour < c.endHourExclusive && c.startHour < b.endHourExclusive }
            }
            val reason = when {
                // The driver's own removal comes first: attributing their edit to thin data would be a lie.
                dayCandidates.isNotEmpty() && available.isEmpty() -> UnpickedReason.REMOVED_BY_YOU
                available.isNotEmpty() -> UnpickedReason.TARGET_ALREADY_MET
                onRecord == 0 -> UnpickedReason.NO_HISTORY
                else -> structuralReason(samples, day)
            }
            UnpickedDay(dayIndex = day, reason = reason, daysOnRecord = onRecord)
        }

    /** Why a weekday with *some* record produced no candidate at all. */
    private fun structuralReason(samples: HourOfWeekSamples, day: Int): UnpickedReason {
        var sampledHours = 0
        var positiveHours = 0
        for (hour in 0 until EarningsHeatmap.HOURS) {
            val rates = samples.occurrenceRates(day, hour, hour + 1)
            if (rates.size < MIN_SAMPLE_DAYS) continue
            sampledHours++
            val median = HourOfWeekSamples.median(rates)
            if (median != null && median > 0.0) positiveHours++
        }
        return when {
            sampledHours == 0 -> UnpickedReason.THIN_HISTORY
            positiveHours == 0 -> UnpickedReason.NO_POSITIVE_RATE
            // Qualifying hours exist but never line up into a run the whole-window sample check accepts.
            else -> UnpickedReason.NO_CONTIGUOUS_RUN
        }
    }
}
