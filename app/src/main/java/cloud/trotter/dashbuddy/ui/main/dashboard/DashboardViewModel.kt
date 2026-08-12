package cloud.trotter.dashbuddy.ui.main.dashboard

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.WeeklyPlanRepository
import cloud.trotter.dashbuddy.core.data.analytics.currentLocalDateFlow
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindows
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanSchedule
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.focusedPlatform
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import javax.inject.Inject

/**
 * State holder for the home screen — **"Today"** since #977 (redesign stage 4 of #969, brief §2).
 * Exposes one immutable [DashboardUiState] (Principle 1 — UDF) assembled reactively from:
 *  - [StateManagerV2] `AppState` — the status pill's vocabulary + the "session active" pointer,
 *  - [AppStateRepository.isFirstRun] — the setup gate,
 *  - [AnalyticsRepository] — the rolling `TODAY` economics ("So far today"), the **lifetime**
 *    hour×day heatmap (the plan strip's only source), and the current pay week's economics /
 *    previous week / per-day net / orphan-offer groups (the week block + the review row).
 *
 * **All of it is existing read-model surface** — stage 4 adds no query. The heatmap is the Patterns
 * tab's read; the week trio is the #970 arbitrary-window read the analytics pager already uses.
 *
 * **The day anchor.** Everything week- or weekday-shaped resolves against [currentLocalDateFlow],
 * which emits and then sleeps until the next local midnight — so the week window re-anchors and the
 * plan strip changes weekday without a restart, and without a per-second ticker (Reactive UI rule 4:
 * this anchor changes once a day, so its flow ticks once a day). The *within-day* tick — the clock
 * and the strip's spent hours — is derived in the composables from `rememberNow()`, per rule 2.
 *
 * The focused platform is resolved through the [Platform] registry (flow's active platform, else
 * most-recent activity) — never a `== DoorDash` literal (Principle 8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val appStateRepository: AppStateRepository,
    stateManager: StateManagerV2,
    private val analyticsRepository: AnalyticsRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val bubbleManager: BubbleManager,
    private val weeklyPlanRepository: WeeklyPlanRepository,
) : ViewModel() {

    /** The device's local date, re-emitting at midnight — the anchor the week + weekday resolve against. */
    private val today: StateFlow<LocalDate> = currentLocalDateFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    /**
     * The pay week's reads, re-anchored atomically on each midnight roll: this week's economics, last
     * week's (the delta), the per-day kept-money axis, and the week's open orphan-offer mismatches.
     *
     * Grouped under one [flatMapLatest] so the four can never describe two different weeks — the same
     * discipline the analytics hub uses for its window fan-out.
     */
    private val weekData: kotlinx.coroutines.flow.Flow<WeekData> = today.flatMapLatest { day ->
        val week = AnalyticsWindows.current(WindowGranularity.WEEK, day)
        combine(
            analyticsRepository.periodEconomics(week),
            previousEconomics(week),
            analyticsRepository.dailyEarnings(week),
            analyticsRepository.orphanOfferGroups(week),
            // #981 — the weekly-plan pointer row. Deliberately the week the driver is IN
            // (`weekStartOf`), not the week a plan saved right now would target: on a Sunday those
            // differ, and Home is describing today.
            weeklyPlanRepository.planFor(WeeklyPlanSchedule.weekStartOf(day)),
        ) { economics, previous, daily, orphanOffers, plan ->
            WeekData(day, economics, previous, daily, orphanOffers, plan)
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        stateManager.state,
        appStateRepository.isFirstRun,
        // The rolling enum overload, deliberately: "so far today" wants the *current* day with the
        // repository's own midnight re-anchoring, not a window pinned at collection time.
        analyticsRepository.periodEconomics(AnalyticsPeriod.TODAY),
        analyticsRepository.earningsHeatmap(),
        weekData,
    ) { state, firstRun, todayEconomics, heatmap, week ->
        val region = focusedRegion(state)
        DashboardUiState(
            isFirstRun = firstRun,
            statusText = statusText(state.regions.flow.flow, region?.mode),
            isDashing = region != null && region.mode != Mode.Offline,
            today = week.today,
            todayEconomics = todayEconomics,
            heatmap = heatmap,
            weekEconomics = week.economics,
            previousWeekEconomics = week.previousEconomics,
            weekDailyEarnings = week.dailyEarnings,
            orphanOfferGroups = week.orphanOfferGroups,
            weeklyPlan = week.weeklyPlan,
            // The live-dash anchor for Home's online figure (#1024 review item 12). Only while the
            // focused platform is actually dashing: a finished session's startedAt would tick a
            // duration that stopped being true when the driver went offline.
            sessionStartedAt = region?.takeIf { it.mode != Mode.Offline }?.session?.startedAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    /**
     * The previous equivalent window's economics. A pay week always has a predecessor, so the null
     * arm is unreachable today — it is kept because [AnalyticsWindows.previous] is the SSOT for
     * "what does this window compare against", and hard-coding `step(-1)` here would be a second
     * copy of that rule.
     */
    private fun previousEconomics(window: AnalyticsWindow) =
        AnalyticsWindows.previous(window)
            ?.let { analyticsRepository.periodEconomics(it) }
            ?: flowOf<PeriodEconomics?>(null)

    /**
     * UDF intent — the week block's `Recap →`. Anchors the Analytics hub on **this** pay week before
     * the caller navigates, by writing the hub's own persisted selection (the DataStore is that
     * selection's single owner, #970 — Home does not get a private copy or a nav argument).
     *
     * A [AnalyticsWindowSelection.Relative] week at offset 0, not an absolute range: "the week Home
     * was showing" must still mean *this* week if the driver comes back tomorrow.
     *
     * Suspends so the caller can order the write before the navigation and the hub opens already on
     * the right window. A failed write is logged and swallowed — it degrades to "the hub opens on
     * whatever it last showed", never to a crash on a tap.
     */
    suspend fun selectWeekRecapWindow() {
        try {
            appPreferencesRepository.setAnalyticsWindow(
                AnalyticsWindowSelection.Relative(WindowGranularity.WEEK, 0),
            )
        } catch (e: CancellationException) {
            throw e // cooperative cancellation — never swallow it
        } catch (e: Exception) {
            // P7: a granularity name and an integer offset only — no PII.
            Timber.tag(TAG).w(e, "recap window selection not persisted")
        }
    }

    fun completeSetup() = viewModelScope.launch {
        appStateRepository.setFirstRunComplete()
    }

    fun showWelcomeBubble() {
        // #428 Half A: the copy itself is owned by BubbleManager (which has Context) — this
        // ViewModel stays Context-free.
        bubbleManager.postWelcomeMessage()
    }

    /** The platform the home status attributes to — registry-resolved, not a literal. */
    private fun focusedRegion(state: AppState): PlatformRegion? =
        state.focusedPlatform()?.let { state.regions.platforms[it] }

    @StringRes
    private fun statusText(flow: Flow, mode: Mode?): Int {
        if (mode == null || mode == Mode.Offline) {
            return when (flow) {
                Flow.SessionEnded -> R.string.dashboard_status_session_complete
                else -> R.string.dashboard_status_ready
            }
        }
        if (mode == Mode.Paused) return R.string.dashboard_status_paused

        return when (flow) {
            Flow.Idle -> R.string.dashboard_status_looking_for_offers
            Flow.OfferPresented -> R.string.dashboard_status_reviewing_offer
            Flow.TaskPickupNavigation, Flow.TaskPickupArrived -> R.string.dashboard_status_heading_to_pickup
            Flow.TaskDropoffNavigation, Flow.TaskDropoffArrived -> R.string.dashboard_status_heading_to_dropoff
            Flow.PostTask -> R.string.dashboard_status_delivery_complete
            // #762 D2: a coarse in-job surface (leg unknown) — honest "active job" status.
            Flow.TaskActive -> R.string.dashboard_status_active_job
            // #736: order unassigned mid-flow — Idle-equivalent (back to looking for offers).
            Flow.TaskUnassigned -> R.string.dashboard_status_looking_for_offers
            Flow.SessionEnded -> R.string.dashboard_status_session_complete
        }
    }

    /** One pay week's worth of read-model, re-anchored atomically under [flatMapLatest]. */
    private data class WeekData(
        val today: LocalDate,
        val economics: PeriodEconomics,
        val previousEconomics: PeriodEconomics?,
        val dailyEarnings: List<DailyEarnings>,
        val orphanOfferGroups: List<OrphanOfferGroup>,
        val weeklyPlan: SavedWeeklyPlan?,
    )

    private companion object {
        const val TAG = "DashboardVm"
    }
}
