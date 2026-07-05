package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * State holder for the Analytics hub (#315 H1) — a **review** surface assembled reactively
 * from the durable analytics read-model ([AnalyticsRepository]), exposing one immutable
 * [AnalyticsUiState] (Principle 1 — UDF). Over the read-model repository surface:
 * `periodEconomics` (frozen-net totals), `perStoreEconomics` (top stores), `recentSessions`
 * (recent dashes), `decisionEconomics` (H3 funnel/verdicts), and `timeEconomics` (H4 time/mileage) —
 * every source session-anchored (#655) via the DAO join, which owns the bucketing.
 *
 * Reactive by construction: every source is a Room-invalidation `Flow`, so the tiles re-emit
 * as the projector folds each delivery and at midnight/week/month rollover (the boundary
 * flow) — fresh-on-open, no `rememberNow()` clock (a historical period's $/hr is a fixed
 * value; same principle as the dashboard reframe #657).
 *
 * Privacy: economics/counts/store names only — customer PII is already sha256'd upstream and
 * never surfaces here (Principle 6); store names are merchants, fine to render (Principle 7
 * governs logs, not this UI). No platform literals — the recent-dashes chip resolves its
 * label through the [cloud.trotter.dashbuddy.domain.state.Platform] registry (Principle 8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    /** UDF intent targets — the selected review window and tab. */
    private val selectedPeriod = MutableStateFlow(AnalyticsPeriod.THIS_WEEK)
    private val selectedTab = MutableStateFlow(AnalyticsTab.Money)

    val uiState: StateFlow<AnalyticsUiState> = combine(
        selectedTab,
        // Re-anchor economics + top stores + decisions together on each period switch so a tile
        // never renders one window's number under another's label. Decisions is collected
        // unconditionally (not gated on the selected tab): the source is a cheap Room-invalidation
        // aggregate and folding it in here keeps one immutable UiState re-anchored atomically with
        // the period — the simpler correct shape (Principle 1).
        selectedPeriod.flatMapLatest { period ->
            combine(
                analyticsRepository.periodEconomics(period),
                analyticsRepository.perStoreEconomics(period),
                analyticsRepository.decisionEconomics(period),
                analyticsRepository.timeEconomics(period),
                analyticsRepository.dailyEarnings(period),
            ) { economics, stores, decisions, time, daily ->
                PeriodData(period, economics, stores, decisions, time, daily)
            }
        },
        analyticsRepository.recentSessions(RECENT_SESSIONS_LIMIT),
    ) { tab, data, sessions ->
        AnalyticsUiState(
            selectedTab = tab,
            selectedPeriod = data.period,
            economics = data.economics,
            topStores = data.stores.take(TOP_STORES),
            recentSessions = sessions,
            decisions = data.decisions,
            time = data.time,
            dailyEarnings = data.dailyEarnings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnalyticsUiState())

    /** UDF intent — switch the review window; economics + top stores re-anchor reactively. */
    fun setPeriod(period: AnalyticsPeriod) {
        selectedPeriod.value = period
    }

    /** UDF intent — switch the visible tab. */
    fun setTab(tab: AnalyticsTab) {
        selectedTab.value = tab
    }

    /** One period switch's worth of read-model, re-anchored atomically under [flatMapLatest]. */
    private data class PeriodData(
        val period: AnalyticsPeriod,
        val economics: PeriodEconomics,
        val stores: List<StoreEconomics>,
        val decisions: DecisionEconomics,
        val time: TimeEconomics,
        val dailyEarnings: List<DailyEarnings>,
    )

    private companion object {
        const val TOP_STORES = 5
        const val RECENT_SESSIONS_LIMIT = 10
    }
}
