package cloud.trotter.dashbuddy.core.database.analytics

/**
 * Aggregate query-result projections for [AnalyticsDao] (#314). Plain POJOs Room
 * maps SUM/COUNT rows into — NOT entities, NOT persisted. They stay in
 * `:core:database` next to the DAO whose queries produce them; the read-side
 * repository (`:core:data`, PR3) folds economy in on top.
 */

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

/** Per-store delivery totals (GROUP BY storeName) — per-store + future chain resolution (#159). */
data class StoreTotalsRow(
    val storeName: String?,
    val pay: Double,
    val net: Double,
    val deliveries: Int,
    /** Σ driver-entered cash tips for the store's period rows (#688) — see [DeliveryTotalsRow.cash]. */
    val cash: Double,
)

/**
 * Reported-authoritative gross + the unattributed delta for a period (#314 PR3).
 * Computed per session (reported summary total when present, else that session's Σ
 * delivered pay), then summed. [unattributed] is the excess of reported over delivered
 * — bonuses/adjustments/a missed capture; a review flag (#650).
 */
data class GrossTotalsRow(
    val gross: Double,
    val unattributed: Double,
)

/** Per-platform gross + unattributed (GROUP BY platform). */
data class PlatformGrossTotalsRow(
    val platform: String,
    val gross: Double,
    val unattributed: Double,
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
