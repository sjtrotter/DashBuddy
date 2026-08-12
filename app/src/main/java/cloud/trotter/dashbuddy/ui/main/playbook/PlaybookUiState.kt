package cloud.trotter.dashbuddy.ui.main.playbook

import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanGrade

/**
 * Immutable state for the **Playbook** (#1024 section C) — the destination that answers *what should I
 * do next*, as opposed to Home (*today*) and Analytics (*what happened*).
 *
 * Everything here is **lifetime-scoped or this-week-scoped by definition** and deliberately ignores the
 * analytics hub's `‹ ›` pager, which is exactly why these surfaces read as strangers inside a
 * period-first hub and why they moved out of it.
 *
 * **No clock of any kind is in this state** (Reactive-UI rule 2). [planGrade] is the anchor; the plan
 * card reads the device clock ONCE per tick and derives BOTH the local date and the hour from that one
 * instant, folding them in through [cloud.trotter.dashbuddy.domain.analytics.PlanProgress]. Two clocks
 * — a flow-supplied date beside a ticker-supplied hour — would skew across local midnight and across a
 * time-zone change, reporting "all planned hours have passed" on a day the plan does not describe.
 *
 * The week being worked likewise has ONE owner per path: with a plan it is the plan's own frozen
 * `weekStart` (which is what the ViewModel matched on to select it), and with no plan the card derives
 * it from the same instant. Neither is copied into this state.
 */
data class PlaybookUiState(
    /** True until the first read-model emission — the screen renders nothing rather than an empty plan. */
    val loading: Boolean = true,
    /**
     * This week's saved plan, frozen at save time. Null when the driver hasn't saved one **or** when
     * the decoded plan holds no windows at all (see [PlaybookViewModel] — a window-less plan is not a
     * plan, and rendering one produces "0 hours planned, $280 projected").
     */
    val savedPlan: SavedWeeklyPlan? = null,
    /**
     * [savedPlan] measured against the driver's own record. Non-null exactly when [savedPlan] is, and
     * the carrier of the week the card labels itself with ([WeeklyPlanGrade.weekStart]).
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
