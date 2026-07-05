package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
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
 * [AnalyticsUiState] (Principle 1 — UDF). Strictly over the EXISTING repository surface:
 * `periodEconomics` (frozen-net totals), `perStoreEconomics` (top stores), `recentSessions`
 * (recent dashes) — no new SQL/DAO aggregation (those are later phases; the #655 bucketing
 * work owns the DAO internals).
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
        // Re-anchor economics + top stores together on each period switch so a tile never
        // renders one window's number under another's label.
        selectedPeriod.flatMapLatest { period ->
            combine(
                analyticsRepository.periodEconomics(period),
                analyticsRepository.perStoreEconomics(period),
            ) { economics, stores -> Triple(period, economics, stores) }
        },
        analyticsRepository.recentSessions(RECENT_SESSIONS_LIMIT),
    ) { tab, (period, economics, stores), sessions ->
        AnalyticsUiState(
            selectedTab = tab,
            selectedPeriod = period,
            economics = economics,
            topStores = stores.take(TOP_STORES),
            recentSessions = sessions,
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

    private companion object {
        const val TOP_STORES = 5
        const val RECENT_SESSIONS_LIMIT = 10
    }
}
