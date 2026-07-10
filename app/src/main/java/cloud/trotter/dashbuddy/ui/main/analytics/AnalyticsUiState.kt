package cloud.trotter.dashbuddy.ui.main.analytics

import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics

/**
 * The Analytics hub tabs (#315). [Money], [Decisions] (H3), [Time] (H4), and [Patterns] (H5) all
 * render real content.
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
    /**
     * Per-day earnings for [selectedPeriod] — the Money-tab earnings-by-day chart (#315 H6). Empty for
     * Today/Lifetime (one bar / unbounded), so the Money tab hides the chart card on an empty list.
     */
    val dailyEarnings: List<DailyEarnings> = emptyList(),
    /** Most recent dash sessions, newest first — each row taps through to the per-dash drill-down (#650). */
    val recentSessions: List<SessionRecord> = emptyList(),
    /** Offer-decision economics for [selectedPeriod] — the Decisions tab (#315 H3, frozen est.). */
    val decisions: DecisionEconomics = DecisionEconomics.EMPTY,
    /** Time / mileage economics for [selectedPeriod] — the Time tab (#315 H4, measured). */
    val time: TimeEconomics = TimeEconomics.EMPTY,
    /**
     * Per-store report cards — the Patterns tab (#315 H5, #159), newest-visited first. **Lifetime-scoped**,
     * NOT period-filtered: the Patterns tab hides the period selector (it is rate/pattern-based).
     */
    val storeReportCards: List<StoreReportCard> = emptyList(),
    /**
     * The driver's own realized net $/hr by hour-of-week — the Patterns tab heatmap (#315 H5).
     * Lifetime-scoped (no period). Empty grid until data accrues.
     */
    val earningsHeatmap: EarningsHeatmap = EarningsHeatmap.EMPTY,
)
