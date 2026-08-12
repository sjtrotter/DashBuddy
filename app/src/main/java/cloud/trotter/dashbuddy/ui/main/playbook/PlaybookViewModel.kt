package cloud.trotter.dashbuddy.ui.main.playbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.WeeklyPlanRepository
import cloud.trotter.dashbuddy.core.data.analytics.currentLocalDateFlow
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanGrader
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanSchedule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * State holder for the **Playbook** (#1024 section C) — UDF state out, no intents in: every surface here
 * is a read of the driver's own record, and the one thing that can be *changed* (the plan itself) is
 * owned by the Weekly Plan screen this one links to.
 *
 * **No new reads.** All four sources already ship: the lifetime heatmap and store report cards were the
 * retired Patterns tab's (#159/#315 H5), the hour-of-week samples are the Weekly Plan's own §7.7 read
 * (#981), and the saved plan comes from the same [WeeklyPlanRepository] Home's pointer row and Sunday's
 * worker use. Nothing new is queried, no schema moves, no `PROJECTOR_VERSION` bump.
 *
 * **The grade is computed here, once.** Grading this week's plan against the record is exactly what the
 * progress card needs (see `PlanProgress`), and doing it in the ViewModel keeps the pure grader as the
 * single owner of "what happened inside these windows" rather than letting a composable re-derive it.
 *
 * Reactive by construction: the read-model sources are Room-invalidation Flows (a dash folded while the
 * screen is open re-grades the week), the plan store is a DataStore Flow, and the local-date flow
 * re-anchors the week at midnight. No `rememberNow()` here — the hour-by-hour freshness the plan card
 * needs is derived at the composable (Reactive-UI rule 2), not pushed through the state.
 *
 * Privacy: aggregate economics, hour-of-week counts and merchant names only. No customer data of any
 * kind reaches this surface (Principle 6), and it emits no logs.
 */
@HiltViewModel
class PlaybookViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val weeklyPlanRepository: WeeklyPlanRepository,
) : ViewModel() {

    val uiState: StateFlow<PlaybookUiState> = combine(
        currentLocalDateFlow(),
        weeklyPlanRepository.savedPlans,
        analyticsRepository.hourOfWeekSamples(),
        analyticsRepository.earningsHeatmap(),
        analyticsRepository.storeReportCards(),
    ) { day, savedPlans, samples, heatmap, storeCards ->
        // The week the driver is IN — `weekStartOf`, not `planWeekStart`: on a Sunday evening those
        // differ, and the Playbook is showing the plan being worked, not the one being drafted (the
        // #981 Home-pointer rule, applied for the same reason).
        val weekStart = WeeklyPlanSchedule.weekStartOf(day)
        // F3 (#1024 review): a plan with NO windows is not a plan. `WeeklyPlanCodec.decode` drops
        // structurally-impossible windows with `mapNotNull` while keeping the plan row, so a corrupt or
        // older-shaped blob can decode to a window-less plan carrying a live `projectedKept` — which
        // renders as "Not started yet — 0 hours scheduled, $280 projected", forever, and outlines
        // nothing on a heatmap that claims cells are outlined. Fail closed at the first consumer: it is
        // the honest no-plan state. (Whether the CODEC should drop such a row instead is a separate
        // decision — it would silently discard a user artifact — so it is deliberately not changed here.)
        val plan = savedPlans.firstOrNull { it.weekStart == weekStart && it.windows.isNotEmpty() }
        PlaybookUiState(
            loading = false,
            savedPlan = plan,
            planGrade = plan?.let { WeeklyPlanGrader.grade(it, samples) },
            heatmap = heatmap,
            storeCards = storeCards,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlaybookUiState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
