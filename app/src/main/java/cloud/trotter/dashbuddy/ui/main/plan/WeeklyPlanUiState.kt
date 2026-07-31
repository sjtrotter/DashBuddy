package cloud.trotter.dashbuddy.ui.main.plan

import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.PlanTarget
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanGrade
import java.time.LocalDate

/**
 * Immutable state for the Weekly Plan screen (#981 / brief §8).
 *
 * Everything on it is either the driver's own record ([heatmap]), a derivation of it ([plan]), or
 * something they themselves committed to ([savedPlan]) — there is no room in this type for a number
 * that came from anywhere else, which is the point.
 */
data class WeeklyPlanUiState(
    /** True until the first record read lands — the screen shows nothing rather than an empty plan. */
    val loading: Boolean = true,
    /** The target the driver selected; drives [plan]. */
    val target: PlanTarget = PlanTarget.DEFAULT,
    /** The plan derived from the record for [target], with the driver's edits replayed. */
    val plan: WeeklyPlan = WeeklyPlan.empty(PlanTarget.DEFAULT),
    /** The lifetime heatmap — "where the plan came from", with the picked cells outlined. */
    val heatmap: EarningsHeatmap = EarningsHeatmap.EMPTY,
    /** Monday of the week this plan is for. */
    val weekStart: LocalDate = LocalDate.now(),
    /** The plan already saved for [weekStart], if any — the Home pointer's source. */
    val savedPlan: SavedWeeklyPlan? = null,
    /** Last week's plan graded against what actually happened; null until there is one to grade. */
    val lastWeekGrade: WeeklyPlanGrade? = null,
) {
    /** True once the driver has any hour on record at all — everything else states thin data instead. */
    val hasRecord: Boolean get() = plan.randomPlacementRate != null

    /** True when a plan for this week is already saved (the button says *update*, not *save*). */
    val isSaved: Boolean get() = savedPlan != null
}
