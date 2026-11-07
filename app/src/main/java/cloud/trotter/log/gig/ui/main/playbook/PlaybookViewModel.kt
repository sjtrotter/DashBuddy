package cloud.trotter.log.gig.ui.main.playbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.AnalyticsRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.analytics.WeeklyPlanRepository
import cloud.trotter.log.gig.core.data.analytics.currentLocalDateFlow
import cloud.trotter.log.gig.domain.analytics.WeeklyPlanGrader
import cloud.trotter.log.gig.domain.analytics.WeeklyPlanSchedule
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject

/**
 * State holder for the **Playbook** (#1024 section C) — UDF state out, preference intents in: every surface here
 * is a read of the driver's own record. Plan editing belongs to the Weekly Plan screen; this
 * ViewModel writes only the shared analytics detail preference.
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
 * A failed read becomes [PlaybookUiState.failed] (one WARN, the failure class only) and [retry]
 * re-subscribes; the records status rides the same combine so REBUILDING holds the cards back.
 *
 * Privacy: aggregate economics, hour-of-week counts and merchant names only. No customer data of any
 * kind reaches this surface (Principle 6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaybookViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val weeklyPlanRepository: WeeklyPlanRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val recordsStatusRepository: RecordsStatusRepository,
) : ViewModel() {

    /** Bumped by [retry]; every bump re-subscribes the Playbook's reads. */
    private val attempts = MutableStateFlow(0)

    /** UDF intent: the failure pattern's Retry. */
    fun retry() {
        attempts.value++
    }

    val uiState: StateFlow<PlaybookUiState> = attempts.flatMapLatest { attempt ->
        playbookState()
            .onStart { if (attempt > 0) emit(PlaybookUiState()) }
            .catch { e ->
                if (e is CancellationException) throw e
                // P7: the failure class only, never record contents.
                Timber.tag(TAG).w(e, "playbook read failed")
                emit(PlaybookUiState(loading = false, failed = true))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlaybookUiState())

    private fun playbookState() = combine(
        currentLocalDateFlow(),
        combine(
            weeklyPlanRepository.savedPlans,
            appPreferencesRepository.analyticsShowMore,
            recordsStatusRepository.status,
        ) { p, m, r -> Triple(p, m, r) },
        analyticsRepository.hourOfWeekSamples(),
        analyticsRepository.earningsHeatmap(),
        analyticsRepository.storeReportCards(),
    ) { day, (savedPlans, more, records), samples, heatmap, storeCards ->
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
            records = records,
            showMore = more,
            savedPlan = plan,
            planGrade = plan?.let { WeeklyPlanGrader.grade(it, samples) },
            heatmap = heatmap,
            storeCards = storeCards,
        )
    }

    fun setShowMore(show: Boolean) {
        viewModelScope.launch { appPreferencesRepository.setAnalyticsShowMore(show) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TAG = "PlaybookVm"
    }
}
