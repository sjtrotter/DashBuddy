package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.annotation.StringRes
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics
import java.time.LocalDate

/**
 * Below this a money figure is effectively zero — no callout, no cash-tip line (it avoids flagging
 * a "$0.00"). ONE owner for the analytics hub (#942): the Money tab and the per-dash drill-down
 * each kept their own `private const` copy of the same 0.005, which is a threshold that can only
 * ever drift apart.
 */
internal const val UNATTRIBUTED_EPSILON = 0.005

/**
 * The Analytics hub tabs (#315). [Money], [Decisions] (H3), [Time] (H4), and [Patterns] (H5) all
 * render real content.
 *
 * [labelRes] is a `@StringRes` id (#428 Half A), resolved at the Compose layer — see
 * `AnalyticsScreen.tabOptions()`. The [AppSegmented][cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented]
 * selection there is keyed off the enum itself, never the resolved label string.
 */
enum class AnalyticsTab(@param:StringRes val labelRes: Int) {
    Money(R.string.analytics_tab_money),
    Patterns(R.string.analytics_tab_patterns),
    Decisions(R.string.analytics_tab_decisions),
    Time(R.string.analytics_tab_time),
}

/**
 * Immutable state for the Analytics hub (Principle 1 — UDF). A **review** surface opened
 * between shifts, so — like the reframed dashboard (#657) — the economics are the durable
 * read-model for the selected [window], reactive to projector commits (Room
 * invalidation) and to local-midnight re-anchoring of the *current* window, but **not** a per-second tick surface: a
 * historical period's $/hr is a fixed value, so no `rememberNow()` clock is needed.
 *
 * Defaults differ from the dashboard on purpose: a review hub opens on the current pay week
 * ([AnalyticsWindowSelection.DEFAULT]) rather than the day, on the [AnalyticsTab.Money] tab.
 * All economics are frozen-net (an economy edit never rewrites a past period) and all
 * fields are read-model-only — nothing here re-enters the pure state machine.
 */
data class AnalyticsUiState(
    val selectedTab: AnalyticsTab = AnalyticsTab.Money,
    /**
     * The selected review window (#970) — an arbitrary `[start, end)` span of local days the `‹ ›`
     * pager walks and the range picker commits. Replaces the old four-value `AnalyticsPeriod`
     * selection; the rolling enum lives on for the home glance, which genuinely wants "the current
     * week" rather than a pageable window.
     */
    val window: AnalyticsWindow = AnalyticsWindowSelection.DEFAULT.resolve(LocalDate.now()),
    /** The device's current local date — the anchor "this week"/"today" labels and the `›` arrow read. */
    val today: LocalDate = LocalDate.now(),
    /** `‹` is live for every window except Lifetime. */
    val canStepBack: Boolean = true,
    /** `›` is live only once the window has moved off the current one (brief §3.1). */
    val canStepForward: Boolean = false,
    /** Frozen-net economics for [window]; fresh-on-open, not live-ticking. */
    val economics: PeriodEconomics = PeriodEconomics.EMPTY,
    /**
     * The **previous equivalent window**'s economics (#970 / brief §7.2) — the recap hero's delta
     * chip compares against this. Null for Lifetime (all time has no predecessor), in which case the
     * hero states that instead of inventing a comparison (§9).
     */
    val previousEconomics: PeriodEconomics? = null,
    /** Top-earning stores for [window] (already capped to the display count). */
    val topStores: List<StoreEconomics> = emptyList(),
    /**
     * Per-day earnings for [window] — the Money-tab earnings-by-day chart (#315 H6) and the recap
     * hero's net sparkline (#970 §7.3). Empty for a single-day, unbounded, or over-long window, so
     * both surfaces hide their chart on an empty list.
     */
    val dailyEarnings: List<DailyEarnings> = emptyList(),
    /** Most recent dash sessions, newest first — each row taps through to the per-dash drill-down (#650). */
    val recentSessions: List<SessionRecord> = emptyList(),
    /**
     * The window's orphan "(No session)" deliveries (#660 piece 2) — the categorize flow's list, opened
     * from the Money-tab callout. Reactive: shrinks as the projector folds a `DELIVERY_SESSION_ASSIGN`.
     */
    val noSessionDeliveries: List<DeliveryRecord> = emptyList(),
    /**
     * The window's still-open orphan-offer mismatches (#810 B2 Tier 2) — accepted offers whose job
     * produced no matching delivery and whose same-store shape the projector's Tier-1 join could not
     * resolve. The Money-tab callout opens the attestation dialog; reactive — shrinks as the driver
     * attests (or Tier-1 resolves) an orphan.
     */
    val orphanOfferGroups: List<OrphanOfferGroup> = emptyList(),
    /** Offer-decision economics for [window] — the Decisions tab (#315 H3, frozen est.). */
    val decisions: DecisionEconomics = DecisionEconomics.EMPTY,
    /** Time / mileage economics for [window] — the Time tab (#315 H4, measured). */
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
