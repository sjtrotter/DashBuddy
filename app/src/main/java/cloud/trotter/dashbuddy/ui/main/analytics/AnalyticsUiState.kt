package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.annotation.StringRes
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.domain.analytics.ANALYTICS_MONEY_EPSILON
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.EstimateVsReality
import cloud.trotter.dashbuddy.domain.analytics.GapStats
import cloud.trotter.dashbuddy.domain.analytics.HourComposition
import cloud.trotter.dashbuddy.domain.analytics.NetPerHourPair
import cloud.trotter.dashbuddy.domain.analytics.OfferFilter
import cloud.trotter.dashbuddy.domain.analytics.OfferListing
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.PayMix
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PlatformEconomics
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
 *
 * #973 pushed that owner one module DOWN to [ANALYTICS_MONEY_EPSILON] (`:domain`), because the pure
 * [PayMix][cloud.trotter.dashbuddy.domain.analytics.PayMix] residue test needs the same threshold
 * and a feature module can never reach an `:app` const. This alias stays so the hub's existing call
 * sites keep reading in their own vocabulary.
 */
internal const val UNATTRIBUTED_EPSILON = ANALYTICS_MONEY_EPSILON

/**
 * The Analytics hub tabs (#315). [Money], [Offers] (#975, was `Decisions`), [Time] (H4), and
 * [Patterns] (H5) all render real content.
 *
 * **Declaration order IS the on-screen order** (`AnalyticsTab.entries` feeds the segmented control),
 * so #975 / brief §1 lands here: Money · Offers · Time · Patterns. This enum is the *only* place the
 * order lives — the selection is transient ViewModel state, never persisted and never part of a nav
 * route, so renaming an entry or reordering the list cannot invalidate a stored preference or a deep
 * link (the hub is one route, `Screen.Analytics`, with no tab argument).
 *
 * [labelRes] is a `@StringRes` id (#428 Half A), resolved at the Compose layer — see
 * `AnalyticsScreen.tabOptions()`. The [AppSegmented][cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented]
 * selection there is keyed off the enum itself, never the resolved label string.
 */
enum class AnalyticsTab(@param:StringRes val labelRes: Int) {
    Money(R.string.analytics_tab_money),
    Offers(R.string.analytics_tab_offers),
    Time(R.string.analytics_tab_time),
    Patterns(R.string.analytics_tab_patterns),
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
    /**
     * How the window's gross broke down — base pay / tips / bonuses & other (#973, brief §4.2/§7.6).
     * Composed at the ViewModel from the window's own gross and the measured pay-mix parts, so the
     * card can never render a mix against a different window's total than the hero above it.
     */
    val payMix: PayMix = PayMix.EMPTY,
    /**
     * The window's economics split per platform (#973, brief §4.2) — one entry per platform with any
     * record in the window, highest gross first. Registry-resolved at the read edge, so this list is
     * whatever the data contains, never a fixed set (Principle 8).
     */
    val platformSplit: List<PlatformEconomics> = emptyList(),
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
    /** Offer-decision economics for [window] — the Offers tab's funnel + declined value (#315 H3, frozen est.). */
    val decisions: DecisionEconomics = DecisionEconomics.EMPTY,
    /**
     * Frozen est. $/hr vs realized net $/hr over the window's matched accepted offers (#975 / brief
     * §7.5). Its own KDoc is the authoritative statement of the population — the surface must state
     * it rather than render two bars as if they covered every accepted offer.
     */
    val estimateVsReality: EstimateVsReality = EstimateVsReality.EMPTY,
    /** Time / mileage economics for [window] — the Time tab (#315 H4, measured). */
    val time: TimeEconomics = TimeEconomics.EMPTY,
    /**
     * The window's between-job gaps (#983 / brief §7.8) — the Time tab's gap card. Measured from the
     * read model's own domain timestamps, session-bounded (never across dashes).
     */
    val gaps: GapStats = GapStats.EMPTY,
    /**
     * "Your typical online hour" (#983 / brief §6) — composed at the ViewModel from [time] + [gaps],
     * both of which describe this same window. Its KDoc is the authoritative derivation.
     */
    val hourComposition: HourComposition = HourComposition.EMPTY,
    /**
     * Net per hour **while working** vs **whole shift** (#983 / brief §6 `4c`) — the pair
     * `docs/design/running-hourly-rate.md` §2a defines (its *active* / *scheduled* denominators).
     * Composed at the ViewModel because its numerator is [economics]' frozen net, which has one owner.
     */
    val netPerHour: NetPerHourPair = NetPerHourPair.EMPTY,
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

/**
 * The Offers tab's list feed (#975 / brief §7.4) — kept OUT of [AnalyticsUiState] on purpose.
 *
 * Its two inputs (the filter chip and the page size) are list-local UI selection that no other tab
 * observes, and folding them into the hub's one immutable state would re-emit every Money/Time tile
 * whenever the driver tapped a chip. Same shape as the range picker's `pickerMonth`/`pickerMonthDays`
 * pair: a sibling `StateFlow` on the same ViewModel, still strictly UDF (state down, intents up).
 *
 * The list stays reactive by construction: both sources are Room-invalidation Flows re-anchored on
 * the hub's selected window, so a projector commit refreshes the visible page without a refresh
 * gesture.
 */
data class OffersFeedState(
    /** Which chip is active — `ALL` shows every outcome. */
    val filter: OfferFilter = OfferFilter.ALL,
    /** The visible page, newest decision first. Every figure is a frozen decision-time estimate. */
    val offers: List<OfferListing> = emptyList(),
    /** How many offers the window holds under [filter] — the `See all N` denominator. */
    val total: Int = 0,
) {
    /** True while the window holds more rows than the page shows AND the ceiling allows more. */
    val canShowMore: Boolean get() = total > offers.size && offers.size < AnalyticsRepository.MAX_OFFER_PAGE

    /**
     * True when the window holds more offers than the read will ever return in one page — the footer
     * says "the most recent N of M" rather than pretending the list is complete (§9).
     */
    val cappedByCeiling: Boolean get() = total > AnalyticsRepository.MAX_OFFER_PAGE
}
