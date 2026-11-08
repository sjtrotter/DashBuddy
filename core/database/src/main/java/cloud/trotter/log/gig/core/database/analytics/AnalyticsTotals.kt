package cloud.trotter.log.gig.core.database.analytics

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
    /** #1459: the session's recorded pay, `SUM(realizedPay)`, null when nothing is priced; the effective report's floor. */
    val deliveredPay: Double?,
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
    /** Pay excluded from SUM(netProfit); never an inferred mileage cost. */
    val payWithoutNet: Double = 0.0,
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
    /** Pay excluded from SUM(netProfit); never an inferred mileage cost. */
    val payWithoutNet: Double = 0.0,
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
    val summaryReported: Double,
    val summaryDashes: Int,
    val counterReported: Double,
    val counterDashes: Int,
    val driverSetReported: Double,
    val driverSetDashes: Int,
    val recordedPayReported: Double,
    val recordedPayDashes: Int,
    val recordedWithoutReport: Double,
    val unreportedDashesWithPay: Int,
    val unreportedDashesNoPay: Int,
    val sessionCash: Double,
)

/** Per-platform gross + unattributed + overAttributed (GROUP BY platform, #701). */
data class PlatformGrossTotalsRow(
    val platform: String,
    val gross: Double,
    val unattributed: Double,
    val overAttributed: Double,
    val summaryReported: Double,
    val summaryDashes: Int,
    val counterReported: Double,
    val counterDashes: Int,
    val driverSetReported: Double,
    val driverSetDashes: Int,
    val recordedPayReported: Double,
    val recordedPayDashes: Int,
    val recordedWithoutReport: Double,
    val unreportedDashesWithPay: Int,
    val unreportedDashesNoPay: Int,
    val sessionCash: Double,
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
    /**
     * The session's FROZEN kept money (#970 §7.3) — Σ `netProfit` + Σ `cashTip` + this session's
     * unattributed remainder, the same three terms `PeriodEconomics.netProfit` folds. Bucketed onto
     * the same start day as [gross], so the per-day net axis decomposes the period net exactly.
     */
    val net: Double = 0.0,
    /**
     * The session's own folded delivery count (#973 / brief §4.2) — the third figure a tapped day bar
     * states. Comes from the same `GROUP BY sessionId` subquery as [gross]/[net], so all three
     * describe one population; a session with nothing folded yet reads 0.
     */
    val deliveries: Int = 0,
)

/**
 * The "(No session)" bucket (#660 piece 1): Σ realized pay + Σ cash tip + count for deliveries whose
 * source event carried NO `sessionId` at all — the same null-session population [DeliveryTotalsRow]
 * already folds into net via its own `completedAt` (#655), but that [GrossTotalsRow]/[SessionGrossRow]
 * never see because those iterate `session_records` and a null-session row joins to nothing. The
 * repository adds [pay] + [cash] into `grossEarnings` so gross can no longer read below the net that
 * already counts this pay, and exposes the same totals as an explicit review signal so an orphaned
 * delivery is a visible data-quality flag, never a silent inconsistency.
 */
data class NoSessionTotalsRow(
    val pay: Double,
    val cash: Double,
    val deliveries: Int,
)

/** Per-platform variant of [NoSessionTotalsRow] (GROUP BY platform) — grouped on the delivery's own
 *  denormalized `platform` column, since there is no session to derive it from. */
data class PlatformNoSessionTotalsRow(
    val platform: String,
    val pay: Double,
    val cash: Double,
    val deliveries: Int,
)

/**
 * One session-less delivery's own completion instant + gross (pay + cash) — the "(No session)"
 * bucket's per-day chart input (#660), mirroring [SessionGrossRow] for rows that join to no session.
 * The repository buckets each onto the LOCAL DAY of its own [completedAt] (there is no session start
 * to anchor on, so this mirrors [DeliveryTotalsRow]'s null-session `completedAt` fallback).
 */
data class NoSessionDailyRow(
    val completedAt: Long,
    val gross: Double,
    /** The row's FROZEN kept money (#970 §7.3) — `netProfit + cashTip`, the terms this bucket
     *  contributes to `PeriodEconomics.netProfit`. */
    val net: Double = 0.0,
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
