package cloud.trotter.dashbuddy.ui.main.analytics

import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics

/**
 * The Analytics hub tabs (#315). Only [Money] has content in H1; the rest ship as the
 * real tab-bar structure with a "coming soon" placeholder so later phases (Decisions H3,
 * Time H4, Patterns H5) slot in without reshaping navigation.
 */
enum class AnalyticsTab(val label: String) {
    Money("Money"),
    Patterns("Patterns"),
    Decisions("Decisions"),
    Time("Time"),
}

/**
 * Immutable state for the Analytics hub (Principle 1 — UDF). A **review** surface opened
 * between shifts, so — like the reframed dashboard (#657) — the economics are the durable
 * read-model for the selected [selectedPeriod], reactive to projector commits (Room
 * invalidation) and midnight/week/month rollover, but **not** a per-second tick surface: a
 * historical period's $/hr is a fixed value, so no `rememberNow()` clock is needed.
 *
 * Defaults differ from the dashboard on purpose: a review hub opens on the week
 * ([AnalyticsPeriod.THIS_WEEK]) rather than the day, on the [AnalyticsTab.Money] tab.
 * All economics are frozen-net (an economy edit never rewrites a past period) and all
 * fields are read-model-only — nothing here re-enters the pure state machine.
 */
data class AnalyticsUiState(
    val selectedTab: AnalyticsTab = AnalyticsTab.Money,
    val selectedPeriod: AnalyticsPeriod = AnalyticsPeriod.THIS_WEEK,
    /** Frozen-net economics for [selectedPeriod]; fresh-on-open, not live-ticking. */
    val economics: PeriodEconomics = PeriodEconomics.EMPTY,
    /** Top-earning stores for [selectedPeriod] (already capped to the display count). */
    val topStores: List<StoreEconomics> = emptyList(),
    /** Most recent dash sessions, newest first (not tappable until #650). */
    val recentSessions: List<SessionRecord> = emptyList(),
)
