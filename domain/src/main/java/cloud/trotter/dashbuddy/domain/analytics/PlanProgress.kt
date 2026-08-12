package cloud.trotter.dashbuddy.domain.analytics

import java.time.LocalDate

/** Where one planned window sits relative to now. */
enum class PlanWindowState {
    /** Its hours have passed. */
    DONE,

    /** The clock is inside it right now. */
    IN_PROGRESS,

    /** It hasn't started yet. */
    UPCOMING,
}

/**
 * One planned window's live progress — the frozen commitment ([window]) beside what the driver's own
 * record says happened in it so far.
 *
 * [actualHours]/[actualKept] come straight off the [GradedWindow] the [WeeklyPlanGrader] produced;
 * this type adds only the elapsed classification, so the numbers on the Playbook and the numbers in
 * Sunday's grade can never come from two different formulas (Principle 5).
 */
data class PlannedWindowProgress(
    val window: SavedPlanWindow,
    val actualHours: Double,
    val actualKept: Double,
    val state: PlanWindowState,
) {
    val plannedHours: Int get() = window.lengthHours

    /** What this window promised when it was saved (`frozen median rate × hours`). */
    val projectedKept: Double get() = window.projectedKept

    /** True once its hours are over and the driver was online for none of them — stated, never scored. */
    val missed: Boolean get() = state == PlanWindowState.DONE && actualHours <= 0.0
}

/**
 * **This week's plan, graded as the week runs** (#1024 section C) — `5h done · $118 kept of the $280
 * you planned for`, with each window marking itself done as its hours pass.
 *
 * ### Why it is a view of [WeeklyPlanGrade], not a second grading
 *
 * Sunday's [WeeklyPlanGrader] already answers "how much of this plan actually happened": it reads the
 * driver's own [HourOfWeekSamples] on the plan week's real dates, inside the planned windows only, and
 * reports what was kept outside them separately. A mid-week progress card asks the SAME question of the
 * SAME inputs — the only difference is that the record cannot contain the future, so grading a live
 * week is **already** elapsed-only by construction. Re-deriving hours-and-dollars here would be a
 * second copy of that formula, and the two would eventually disagree about what "kept in this window"
 * means.
 *
 * So [of] takes a finished [WeeklyPlanGrade] and adds exactly one thing the grader has no business
 * knowing: **where the clock is**. Everything else is read through.
 *
 * ### What "elapsed" means, precisely (§9)
 *
 * [elapsedPlannedHours] counts a window's hours only once they are *over*: a window in progress
 * contributes its completed hours, never the one being lived through. That is hour granularity on
 * purpose — [HourOfWeekSamples] buckets the record by wall-clock hour, so a finer claim would be a
 * precision the evidence does not have.
 *
 * **It is a WALL-CLOCK count, and on a DST-forward day that is one hour generous.** A window spanning
 * the skipped hour reports `endHour − startHour` elapsed while only `length − 1` real hours passed, so
 * "hours still ahead" reads one low that day (`PlanProgressTest` pins this as characterization). It is
 * not corrected here because correcting it needs a `ZoneId`, and taking a zone would make this type
 * clock-aware — the one thing keeping it purely testable. The bound is one hour, twice a year, on the
 * SCHEDULE side only: [hoursDone]/[keptSoFar] come from the sampler, which apportions real elapsed
 * milliseconds, so the *worked* side can never be overstated by DST.
 *
 * Pure `:domain`: the plan, the record and the clock reading go in; no wall clock is read here
 * (Principle 1), so every arm — before the week, mid-window, after the week — is a plain unit test.
 */
data class PlanProgress(
    val weekStart: LocalDate,
    /** Hours the plan placed, all week. */
    val plannedHours: Int,
    /** What the whole plan projected, frozen at save time. */
    val projectedKept: Double,
    /** Hours actually worked inside the planned windows so far. */
    val hoursDone: Double,
    /** Frozen net + cash kept inside the planned windows so far. */
    val keptSoFar: Double,
    /** Of [plannedHours], how many have already passed (see the class KDoc on hour granularity). */
    val elapsedPlannedHours: Int,
    /** Kept this week *outside* the planned windows — reported, never folded into [keptSoFar]. */
    val keptOutsideWindows: Double,
    /** Per-window detail, in the saved plan's own order. */
    val windows: List<PlannedWindowProgress>,
) {
    /** Scheduled hours still ahead of the driver this week. */
    val plannedHoursLeft: Int get() = (plannedHours - elapsedPlannedHours).coerceAtLeast(0)

    /** The window the clock is inside right now, if any — the reason [notStarted] is not just an hour count. */
    val currentWindow: PlannedWindowProgress? get() = windows.firstOrNull { it.state == PlanWindowState.IN_PROGRESS }

    /** True before any planned hour has passed — the card says "not started yet" rather than "0 of 12h". */
    val notStarted: Boolean get() = elapsedPlannedHours == 0 && currentWindow == null

    /** True once every planned window is over — the week's plan is finished, whatever it returned. */
    val finished: Boolean get() = windows.isNotEmpty() && windows.all { it.state == PlanWindowState.DONE }

    companion object {

        /**
         * [grade] is this week's saved plan graded against the record; [today] and [currentHour] are the
         * device's local clock reading, taken at the UI edge (Reactive-UI rule 2 — the state holds the
         * anchors, the composable derives the hour from its ticker and calls this).
         *
         * A [today] outside the plan's week is handled by the same comparison the mid-week case uses:
         * a date before every window makes them all [PlanWindowState.UPCOMING], a date after makes them
         * all [PlanWindowState.DONE]. No special-casing, so a stale plan can never render as in-progress.
         */
        fun of(grade: WeeklyPlanGrade, today: LocalDate, currentHour: Int): PlanProgress {
            val hour = currentHour.coerceIn(0, EarningsHeatmap.HOURS - 1)
            val windows = grade.windows.map { graded ->
                PlannedWindowProgress(
                    window = graded.window,
                    actualHours = graded.actualHours,
                    actualKept = graded.actualKept,
                    state = stateOf(
                        date = grade.weekStart.plusDays(graded.window.dayIndex.toLong()),
                        window = graded.window,
                        today = today,
                        hour = hour,
                    ),
                )
            }
            return PlanProgress(
                weekStart = grade.weekStart,
                plannedHours = grade.plannedHours,
                projectedKept = grade.projectedKept,
                hoursDone = grade.actualHours,
                keptSoFar = grade.actualKept,
                elapsedPlannedHours = windows.sumOf { elapsedHoursOf(it, hour) },
                keptOutsideWindows = grade.keptOutsideWindows,
                windows = windows,
            )
        }

        private fun stateOf(
            date: LocalDate,
            window: SavedPlanWindow,
            today: LocalDate,
            hour: Int,
        ): PlanWindowState = when {
            date.isBefore(today) -> PlanWindowState.DONE
            date.isAfter(today) -> PlanWindowState.UPCOMING
            hour >= window.endHourExclusive -> PlanWindowState.DONE
            hour < window.startHour -> PlanWindowState.UPCOMING
            else -> PlanWindowState.IN_PROGRESS
        }

        /** Completed hours only — the hour being lived through is not counted (see the class KDoc). */
        private fun elapsedHoursOf(progress: PlannedWindowProgress, hour: Int): Int = when (progress.state) {
            PlanWindowState.DONE -> progress.plannedHours
            PlanWindowState.UPCOMING -> 0
            PlanWindowState.IN_PROGRESS -> (hour - progress.window.startHour).coerceIn(0, progress.plannedHours)
        }
    }
}
