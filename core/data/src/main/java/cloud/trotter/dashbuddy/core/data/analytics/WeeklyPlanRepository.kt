package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.datastore.plan.WeeklyPlanDataSource
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The driver's saved weekly plans (#981 / brief §8 item 6) — write side of the plan surface, read side
 * of the Home pointer row and the Sunday grading worker.
 *
 * Thin by design: the store moves a string, the `:domain` [WeeklyPlanCodec] owns the shape (fail-closed
 * in both directions), and this class owns exactly one policy — **how many plans are kept and which
 * one wins for a week**. It is deliberately NOT part of `AnalyticsRepository`: a saved plan is a user
 * artifact rather than a projection of the event log, and mixing it into the read-model repository
 * would blur the "analytics is rebuildable, this is not" line that keeps a `PROJECTOR_VERSION` bump
 * from deleting it (see `WeeklyPlanDataSource`).
 */
@Singleton
class WeeklyPlanRepository @Inject constructor(
    private val dataSource: WeeklyPlanDataSource,
) {

    /** Every kept plan, newest week first. Empty when nothing has ever been saved (or the blob is corrupt). */
    val savedPlans: Flow<List<SavedWeeklyPlan>> =
        dataSource.savedPlansJson.map { json ->
            WeeklyPlanCodec.decode(json).sortedByDescending { it.weekStart }
        }

    /** The plan saved for the week starting [weekStart], or null. */
    fun planFor(weekStart: LocalDate): Flow<SavedWeeklyPlan?> =
        savedPlans.map { plans -> plans.firstOrNull { it.weekStart == weekStart } }

    /** One-shot read of the plan for [weekStart] — the worker's path (it has no UI to keep collecting). */
    suspend fun planForNow(weekStart: LocalDate): SavedWeeklyPlan? =
        savedPlans.first().firstOrNull { it.weekStart == weekStart }

    /**
     * Persist [plan], replacing any existing plan for the same week and keeping at most
     * [SavedWeeklyPlan.MAX_KEPT] of the most recent weeks.
     *
     * Re-saving a week is a plain overwrite: the driver rearranged their plan and the newer arrangement
     * is the one they committed to, so it is also the one next Sunday grades.
     */
    suspend fun save(plan: SavedWeeklyPlan) {
        val kept = (listOf(plan) + savedPlans.first().filterNot { it.weekStart == plan.weekStart })
            .sortedByDescending { it.weekStart }
            .take(SavedWeeklyPlan.MAX_KEPT)
        dataSource.savePlansJson(WeeklyPlanCodec.encode(kept))
    }

    /** Drop the plan for [weekStart] (the driver clearing it), leaving the others untouched. */
    suspend fun delete(weekStart: LocalDate) {
        val kept = savedPlans.first().filterNot { it.weekStart == weekStart }
        dataSource.savePlansJson(WeeklyPlanCodec.encode(kept))
    }
}
