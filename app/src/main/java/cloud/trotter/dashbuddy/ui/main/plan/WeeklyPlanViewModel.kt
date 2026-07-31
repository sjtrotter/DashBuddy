package cloud.trotter.dashbuddy.ui.main.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.WeeklyPlanRepository
import cloud.trotter.dashbuddy.core.data.analytics.currentLocalDateFlow
import cloud.trotter.dashbuddy.domain.analytics.HourOfWeekSamples
import cloud.trotter.dashbuddy.domain.analytics.PlanEdit
import cloud.trotter.dashbuddy.domain.analytics.PlanTarget
import cloud.trotter.dashbuddy.domain.analytics.PlannedWindow
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanGrader
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanSchedule
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * The Weekly Plan screen's ViewModel (#981 / brief §8).
 *
 * **UDF, with the derivation kept pure.** The ViewModel holds only what the driver has *chosen* — the
 * target and an ordered list of [PlanEdit]s — and recomputes the whole plan from the record on every
 * emission via the pure [WeeklyPlanner]. Nothing is mutated in place, so a moved window re-derives its
 * evidence at its new hours instead of carrying a number measured somewhere else, and the state can
 * never drift from what the algorithm would produce.
 *
 * **Reactive by construction:** the record is a Room-invalidation `Flow`, so a dash folded while the
 * screen is open re-plans it; the local-date flow re-anchors the plan week at midnight without a
 * restart (the #970/#977 pattern). No `rememberNow()` clock is needed — a week's plan is not a ticking
 * value.
 *
 * **Privacy:** the whole surface is aggregate economics and hour-of-week counts. No store names, no
 * customer data, no PII of any kind reaches it (Principle 6), and it emits no logs.
 */
@HiltViewModel
class WeeklyPlanViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val weeklyPlanRepository: WeeklyPlanRepository,
) : ViewModel() {

    /** UDF intent target — the driver's chosen target. Transient: a plan is saved, a target is not. */
    private val target = MutableStateFlow(PlanTarget.DEFAULT)

    /**
     * The driver's edits, in order, replayed over a freshly-derived base plan. Cleared whenever the
     * target changes: the windows an 8-hour plan holds are not the windows a 20-hour plan holds, so
     * replaying "drop the third one" across that switch would move a window the driver never touched.
     */
    private val edits = MutableStateFlow<List<PlanEdit>>(emptyList())

    /** The device's local date, re-emitting at midnight — what decides which week is being planned. */
    private val today: StateFlow<LocalDate> = currentLocalDateFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    val uiState: StateFlow<WeeklyPlanUiState> = combine(
        target,
        edits,
        today,
        analyticsRepository.hourOfWeekSamples(),
        combine(analyticsRepository.earningsHeatmap(), weeklyPlanRepository.savedPlans) { heatmap, saved ->
            heatmap to saved
        },
    ) { target, edits, day, samples, (heatmap, savedPlans) ->
        val weekStart = WeeklyPlanSchedule.planWeekStart(day)
        WeeklyPlanUiState(
            loading = false,
            target = target,
            plan = WeeklyPlanner.plan(samples, target, edits),
            heatmap = heatmap,
            weekStart = weekStart,
            savedPlan = savedPlans.firstOrNull { it.weekStart == weekStart },
            lastWeekGrade = gradeOfLastFinishedPlan(savedPlans, samples, day),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WeeklyPlanUiState())

    /** Change the target; the plan re-derives and the driver's edits are dropped with it. */
    fun setTarget(newTarget: PlanTarget) {
        if (target.value == newTarget) return
        target.value = newTarget
        edits.value = emptyList()
    }

    /** Drop a window; the planner re-fills from the next-best candidate that isn't in dropped territory. */
    fun dropWindow(window: PlannedWindow) {
        edits.value = edits.value + PlanEdit.Drop(window.dayIndex, window.startHour)
    }

    /**
     * Move a window [deltaHours] earlier (negative) or later (positive).
     *
     * This is the brief's "drag to move a window", as a pair of one-hour nudges. A true drag gesture
     * would have to express *moving a window along a time axis* — not reordering a list — and in a
     * vertically-scrolling list of rows that reads as a reorder, which is the one thing it must not
     * mean. The nudge is unambiguous, reachable one-handed while parked, and hits the same intent.
     */
    fun moveWindow(window: PlannedWindow, deltaHours: Int) {
        edits.value = edits.value + PlanEdit.Shift(window.dayIndex, window.startHour, deltaHours)
    }

    /** Undo the most recent edit — the drop/move affordances would otherwise be one-way. */
    fun undoLastEdit() {
        edits.value = edits.value.dropLast(1)
    }

    /**
     * Save the plan as it currently stands, frozen (see [SavedWeeklyPlan]).
     *
     * [savedAtMillis] is the device clock at the save edge — the one wall-clock read this feature's
     * UI layer makes, kept here at the edge rather than inside the pure derivation (Principle 1).
     */
    fun savePlan(savedAtMillis: Long = System.currentTimeMillis()) {
        val state = uiState.value
        if (state.loading) return
        viewModelScope.launch {
            weeklyPlanRepository.save(SavedWeeklyPlan.from(state.plan, state.weekStart, savedAtMillis))
        }
    }

    /**
     * The most recent saved plan whose week has fully ended, graded against the record.
     *
     * "Fully ended" is load-bearing: grading a week still being worked would report a shortfall the
     * driver has not had the chance to work off yet.
     */
    private fun gradeOfLastFinishedPlan(
        savedPlans: List<SavedWeeklyPlan>,
        samples: HourOfWeekSamples,
        today: LocalDate,
    ) = savedPlans
        .filter { !it.weekStart.plusWeeks(1).isAfter(today) }
        .maxByOrNull { it.weekStart }
        ?.let { WeeklyPlanGrader.grade(it, samples) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
