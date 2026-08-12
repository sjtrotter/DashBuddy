package cloud.trotter.dashbuddy.ui.main.playbook

import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanGrade
import java.time.LocalDate

/**
 * Immutable state for the **Playbook** (#1024 section C) — the destination that answers *what should I
 * do next*, as opposed to Home (*today*) and Analytics (*what happened*).
 *
 * Everything here is **lifetime-scoped or this-week-scoped by definition** and deliberately ignores the
 * analytics hub's `‹ ›` pager, which is exactly why these surfaces read as strangers inside a
 * period-first hub and why they moved out of it.
 *
 * **The clock is not in this state** (Reactive-UI rule 2). [planGrade] and [today] are the anchors; the
 * plan card derives the current hour from its own ticker and folds it in through
 * [cloud.trotter.dashbuddy.domain.analytics.PlanProgress], so a window marks itself done on the hour
 * boundary without a state emission and without a per-second re-render of the whole screen.
 */
data class PlaybookUiState(
    /** True until the first read-model emission — the screen renders nothing rather than an empty plan. */
    val loading: Boolean = true,
    /** The device's current local date, re-emitting at midnight. */
    val today: LocalDate = LocalDate.now(),
    /** Monday of the week the driver is IN (not the week a plan saved now would target). */
    val weekStart: LocalDate = LocalDate.now(),
    /** This week's saved plan, frozen at save time. Null when the driver hasn't saved one. */
    val savedPlan: SavedWeeklyPlan? = null,
    /**
     * [savedPlan] measured against the driver's own record. Non-null exactly when [savedPlan] is.
     *
     * Grading a week that is still running is not a mistake here: the record cannot contain the future,
     * so the grader's numbers ARE the elapsed-so-far numbers — which is what lets the progress card be a
     * view of the same grade Sunday's notification reports rather than a second formula (see
     * `PlanProgress`).
     */
    val planGrade: WeeklyPlanGrade? = null,
    /** The lifetime hour-of-week heatmap — "when you earn", with the plan's picked cells outlined. */
    val heatmap: EarningsHeatmap = EarningsHeatmap.EMPTY,
    /** The lifetime per-store report cards — "where you earn", as one sortable leaderboard. */
    val storeCards: List<StoreReportCard> = emptyList(),
)
