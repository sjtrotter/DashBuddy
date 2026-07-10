package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Embedded

/**
 * Aggregate query-result projections for [AnalyticsDao] (#314). Plain POJOs Room
 * maps SUM/COUNT rows into — NOT entities, NOT persisted. They stay in
 * `:core:database` next to the DAO whose queries produce them; the read-side
 * repository (`:core:data`, PR3) folds economy in on top.
 */

/**
 * One recent session row + its Σ driver-entered cash tips (#688 F7). [session] is the whole
 * `session_records` row (`@Embedded`); [cash] is `COALESCE(SUM(cashTip), 0)` over that session's
 * delivery rows — a LEFT-JOINed `GROUP BY sessionId` subquery (one row per session, no fan-out, the
 * same shape as `AnalyticsDao.sessionGrossRows`). Kept as its OWN column — never folded into the
 * session row — so the recent-dashes list can show a "+cash" marker WITHOUT rewriting the
 * platform-reported earnings number (the label stays honest; cash is additive-visible only).
 */
data class SessionWithCashRow(
    @Embedded val session: SessionRecordEntity,
    val cash: Double,
)

/**
 * Cross-platform delivery totals for a period. [net] is Σ **frozen** per-delivery net
 * (the projector's `netProfit`, costed against each accepted offer's basis) — an economy
 * edit never re-costs it (#314 PR3).
 */
data class DeliveryTotalsRow(
    val pay: Double,
    val net: Double,
    val deliveries: Int,
    val jobs: Int,
    /**
     * Σ driver-entered cash tips = `COALESCE(SUM(cashTip), 0)` (#688). Kept as its OWN alias — NOT
     * folded into [pay]/[net] — so the locked accounting adds cash to gross/net explicitly at the
     * repository while the reconciliation's Σ-attributed ([pay]) stays cash-free.
     */
    val cash: Double,
    /**
     * Σ **frozen** fuel dollars for the period = `SUM(frozenFuelPerMile × realizedMiles)` (#659) —
     * the first-class fuel cost row of the 4-step true-net waterfall. Nullable and left un-`COALESCE`d
     * on purpose: SQL `SUM` of all-null terms is NULL, which is the "no frozen split coverage" signal
     * (the waterfall falls back to 3-step). Non-negative by construction (per-mile ≥ 0, miles floored
     * ≥ 0), so it can never render a negative cost row (the #662-F1 fix — cost is not gross−net).
     */
    val fuelCost: Double?,
    /** Σ frozen non-fuel dollars = `SUM(frozenNonFuelPerMile × realizedMiles)`; same null-coverage rule (#659). */
    val nonFuelCost: Double?,
)

/** Per-platform delivery totals (GROUP BY platform). */
data class PlatformDeliveryTotalsRow(
    val platform: String,
    val pay: Double,
    val net: Double,
    val deliveries: Int,
    val jobs: Int,
    /** Σ driver-entered cash tips for the platform's period rows (#688) — see [DeliveryTotalsRow.cash]. */
    val cash: Double,
    /** Σ frozen fuel dollars for the platform's period rows (#659) — see [DeliveryTotalsRow.fuelCost]. */
    val fuelCost: Double?,
    /** Σ frozen non-fuel dollars for the platform's period rows (#659). */
    val nonFuelCost: Double?,
)

/**
 * Per-(storeKey, storeName, platform) delivery totals (#159 F9 raw input). [storeKey] is null for an
 * unresolved row; [platform] lets the repository fold every row to `platform + "|" + normalizedChain`
 * (chain from the storeKey's middle segment when keyed, else the normalizer over [storeName]), merging
 * a resolved keyed location and its unresolved chain form into ONE bucket. [storeName] is a
 * representative raw form of the group (the display name prefers `stores.chainDisplay`).
 */
data class StoreTotalsRow(
    val storeKey: String?,
    val storeName: String?,
    val platform: String,
    val pay: Double,
    val net: Double,
    val deliveries: Int,
    /** Σ driver-entered cash tips for the store's period rows (#688) — see [DeliveryTotalsRow.cash]. */
    val cash: Double,
)

/** First-observed chain-display capitalization per (platform, normalizedChain) — the F9 rollup's
 *  display-name source (#159). */
data class StoreChainDisplayRow(
    val platform: String,
    val normalizedChain: String,
    val chainDisplay: String,
)

/**
 * One store's report-card rollup (#159, the #315 Patterns tab) — the `stores` metadata plus
 * derived-at-read pickup/delivery counts and realized gross/net. Dwell percentiles are computed in the
 * repository from [StoreDwellSample]. A [runningKey] of null is a **chain-only ("location unknown")**
 * bucket (F6): its dwell population blends multiple physical locations, so per-location stats are
 * partial by construction.
 */
data class StoreReportRow(
    val storeKey: String,
    val platform: String,
    val normalizedChain: String,
    val chainDisplay: String,
    val runningKey: String?,
    val address: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val pickups: Int,
    val deliveries: Int,
    val gross: Double,
    val net: Double,
)

/** One pickup dwell sample (`confirmedAt − arrivedAt`) keyed by storeKey — the raw input the repository
 *  folds into per-store avg/p50/p95 (#159; SQLite has no native percentile). */
data class StoreDwellSample(
    val storeKey: String?,
    val dwellMillis: Long,
)

/**
 * Reported-authoritative gross + the unattributed delta for a period (#314 PR3).
 * Computed per session (reported summary total when present, else that session's Σ
 * delivered pay), then summed. [unattributed] is the excess of reported over delivered
 * — bonuses/adjustments/a missed capture; a review flag (#650). [overAttributed] is the
 * mirror excess of delivered over reported (#701) — a **display-only** review signal, never
 * folded into [unattributed]/`netProfit`.
 */
data class GrossTotalsRow(
    val gross: Double,
    val unattributed: Double,
    val overAttributed: Double,
)

/** Per-platform gross + unattributed + overAttributed (GROUP BY platform, #701). */
data class PlatformGrossTotalsRow(
    val platform: String,
    val gross: Double,
    val unattributed: Double,
    val overAttributed: Double,
)

/**
 * One session's start instant + reported-authoritative gross (#315 H6) — the per-day earnings-chart
 * input, one row per session started in the window. [gross] = the summary-screen `reportedEarnings`
 * when present, else that session's Σ delivered pay (the same per-session definition as
 * [GrossTotalsRow]); the repository buckets it onto [startedAt]'s local day (session-anchored, #655).
 */
data class SessionGrossRow(
    val startedAt: Long,
    val gross: Double,
)

/** Cross-platform session totals for a period: miles = Σ odo delta, onlineMillis = Σ duration, sessions = COUNT. */
data class SessionTotalsRow(
    val miles: Double,
    val onlineMillis: Long,
    /** Dashes (sessions) that started in the period — the Time-tab dash count / avg-dash denominator (#315 H4). */
    val sessions: Int,
)

/**
 * One session's online span (#315 H5 Patterns heatmap) — [startMillis] = `startedAt`, [endMillis] =
 * effective end `COALESCE(endedAt, lastEventAt)` (a still-open session uses its last event). The
 * repository apportions this span across the hour-of-week grid's coverage denominator. Lifetime-scoped
 * (the heatmap has no period selector), so the query is unbounded.
 */
data class SessionSpanRow(
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * One completed delivery's net + completion instant (#315 H5 Patterns heatmap numerator).
 * [netDollars] = `COALESCE(netProfit, 0) + COALESCE(cashTip, 0)` — the frozen realized net plus the
 * driver-entered cash tip (#688; cash lives outside `netProfit`, added at the read site). A null-net
 * (no cost basis) row contributes its cash only — the frozen-net philosophy, never recomputed.
 */
data class DeliveryNetRow(
    val completedAt: Long,
    val netDollars: Double,
)

/** Per-platform session totals (GROUP BY platform). */
data class PlatformSessionTotalsRow(
    val platform: String,
    val miles: Double,
    val onlineMillis: Long,
    /** Dashes for the platform's period rows (#315 H4). */
    val sessions: Int,
)

/**
 * Time-tab delivery aggregates for a period (#315 H4) — session-anchored (#655), same WHERE shape as
 * [DeliveryTotalsRow]. [deliveryMinutes]/[deliveryMiles] are Σ per-delivery realized **partition
 * deltas** (nullable and left un-`COALESCE`d: SQL `SUM` of an empty set is NULL — the "nothing
 * measured" signal, never a fabricated 0 miles/minutes). [withDeadline]/[onTime] cover ONLY
 * deadline-carrying rows (a delivery with no captured deadline is excluded from both, never counted
 * as late). [avgDeadlineMarginMillis] = `AVG(deadlineMillis − completedAt)` over deadline-carrying
 * rows, positive when the driver typically finished early (nullable — null when no row carried a
 * deadline).
 */
data class DeliveryTimeTotalsRow(
    val deliveryMinutes: Double?,
    val deliveryMiles: Double?,
    val withDeadline: Int,
    val onTime: Int,
    val avgDeadlineMarginMillis: Double?,
)

/**
 * Per-outcome offer counts + Σ frozen `estNetPay` for a period (#315 H3, Decisions tab). One row
 * per closing [outcome] ("OFFER_ACCEPTED" / "OFFER_DECLINED" / "OFFER_TIMEOUT") — the funnel counts
 * and the value-of-saying-no input (Σ est net of the declined group). Estimates are the offer's
 * FROZEN decision-time snapshot (an economy edit never re-costs them), never realized net.
 */
data class OutcomeCountRow(
    val outcome: String,
    val count: Int,
    val estNetSum: Double,
)

/**
 * Per-outcome score / est-$/hr averages for a period (#315 H3) — the "is my judgment matching the
 * verdicts" read. [avgScore]/[avgEstPerHour] are SQL `AVG`s (nullable — a group whose rows all
 * carried a null `score`/`estDollarsPerHour` yields null, never a fabricated 0). Frozen estimates.
 */
data class ScoreOutcomeRow(
    val outcome: String,
    val count: Int,
    val avgScore: Double?,
    val avgEstPerHour: Double?,
)
