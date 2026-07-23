package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.CorrectionRepository
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * State holder for the Analytics hub (#315 H1) — a **review** surface assembled reactively
 * from the durable analytics read-model ([AnalyticsRepository]), exposing one immutable
 * [AnalyticsUiState] (Principle 1 — UDF). Over the read-model repository surface:
 * `periodEconomics` (frozen-net totals), `perStoreEconomics` (top stores), `recentSessions`
 * (recent dashes), `decisionEconomics` (H3 funnel/verdicts), and `timeEconomics` (H4 time/mileage) —
 * every *period* source session-anchored (#655) via the DAO join, which owns the bucketing. The
 * Patterns tab's `storeReportCards` (#159) + `earningsHeatmap` (H5) are LIFETIME-scoped and sit
 * outside the period `flatMapLatest`, so a period switch never re-queries them.
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
    private val analyticsRepository: AnalyticsRepository,
    private val correctionRepository: CorrectionRepository,
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
                // The typed `combine` tops out at 5 flows, so the per-day chart + the "(No session)"
                // orphan list (#660 piece 2) + the orphan-OFFER groups (#810 B2 Tier 2) ride ONE nested
                // sub-combine into the 5th slot. All period-anchored, so they re-anchor atomically with
                // everything else on a period switch.
                combine(
                    analyticsRepository.dailyEarnings(period),
                    analyticsRepository.noSessionDeliveries(period),
                    analyticsRepository.orphanOfferGroups(period),
                ) { daily, orphans, offerGroups -> Triple(daily, orphans, offerGroups) },
            ) { economics, stores, decisions, time, (daily, orphans, offerGroups) ->
                PeriodData(period, economics, stores, decisions, time, daily, orphans, offerGroups)
            }
        },
        analyticsRepository.recentSessions(RECENT_SESSIONS_LIMIT),
        // The Patterns tab (H5, #159) is LIFETIME-scoped by design — its sources sit OUTSIDE the
        // period flatMapLatest so switching the Money/Decisions/Time period never re-queries them.
        analyticsRepository.storeReportCards(),
        analyticsRepository.earningsHeatmap(),
    ) { tab, data, sessions, storeCards, heatmap ->
        AnalyticsUiState(
            selectedTab = tab,
            selectedPeriod = data.period,
            economics = data.economics,
            topStores = data.stores.take(TOP_STORES),
            recentSessions = sessions,
            decisions = data.decisions,
            time = data.time,
            dailyEarnings = data.dailyEarnings,
            noSessionDeliveries = data.orphanDeliveries,
            orphanOfferGroups = data.orphanOfferGroups,
            storeReportCards = storeCards,
            earningsHeatmap = heatmap,
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

    /**
     * The candidate dashes an orphan could be categorized into (#660 piece 2) — a one-shot suspend read
     * the picker runs in a `produceState` when the driver taps an orphan. Delegates to the repository's
     * ±48 h / same-platform / ended-only policy (the pre-filter; the projector's ended-session guard is
     * the authority).
     */
    suspend fun candidateSessionsFor(orphan: DeliveryRecord): List<SessionRecord> =
        analyticsRepository.candidateSessionsForOrphan(orphan.completedAt, orphan.platform)

    /**
     * UDF intent (#660 piece 2) — categorize an orphan delivery into [newSessionId] by appending a
     * `DELIVERY_SESSION_ASSIGN`. The projector applies it (with its fail-closed guards) and Room
     * invalidation refreshes the callout + the orphan list — no optimistic local mutation.
     */
    fun assignToSession(targetEventSequenceId: Long, newSessionId: String) {
        viewModelScope.launch {
            // Fail-closed backstop — a rejected append (blank id) must not crash the launch (parity with
            // the SessionDetail corrections). Alpha-acceptable silent reject; the projector is authoritative.
            try {
                correctionRepository.assignDeliverySession(
                    targetEventSequenceId = targetEventSequenceId,
                    newSessionId = newSessionId,
                )
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow it
            } catch (e: Exception) {
                // P7: counts/ids only.
                Timber.tag(TAG).w(e, "assignToSession rejected for delivery seq %d", targetEventSequenceId)
            }
        }
    }

    /**
     * UDF intent (#810 B2 Tier 2) — attest that an accepted offer was invisibly unassigned (or UNDO)
     * by appending an `OFFER_OUTCOME_CORRECTION`. The projector stamps `offer_records.outcomeResolved`
     * (with its fail-closed guards) and Room invalidation refreshes the callout + the open-mismatch
     * list — no optimistic local mutation. [attested] false is the undo (clears the resolution).
     */
    fun resolveOrphanOffer(offerEventSequenceId: Long, attested: Boolean) {
        viewModelScope.launch {
            try {
                correctionRepository.correctOfferOutcome(
                    targetOfferEventSequenceId = offerEventSequenceId,
                    attested = attested,
                )
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow it
            } catch (e: Exception) {
                // P7: counts/ids only.
                Timber.tag(TAG).w(e, "resolveOrphanOffer rejected for offer seq %d", offerEventSequenceId)
            }
        }
    }

    /** One period switch's worth of read-model, re-anchored atomically under [flatMapLatest]. */
    private data class PeriodData(
        val period: AnalyticsPeriod,
        val economics: PeriodEconomics,
        val stores: List<StoreEconomics>,
        val decisions: DecisionEconomics,
        val time: TimeEconomics,
        val dailyEarnings: List<DailyEarnings>,
        val orphanDeliveries: List<DeliveryRecord>,
        val orphanOfferGroups: List<OrphanOfferGroup>,
    )

    private companion object {
        const val TOP_STORES = 5
        const val RECENT_SESSIONS_LIMIT = 10
        const val TAG = "AnalyticsVm"
    }
}
