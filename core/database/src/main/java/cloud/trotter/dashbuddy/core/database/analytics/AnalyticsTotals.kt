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
)

/** Per-platform delivery totals (GROUP BY platform). */
data class PlatformDeliveryTotalsRow(
    val platform: String,
    val pay: Double,
    val net: Double,
    val deliveries: Int,
    val jobs: Int,
)

/** Per-store delivery totals (GROUP BY storeName) — per-store + future chain resolution (#159). */
data class StoreTotalsRow(
    val storeName: String?,
    val pay: Double,
    val net: Double,
    val deliveries: Int,
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

/** Cross-platform session totals for a period: miles = Σ odo delta, onlineMillis = Σ duration. */
data class SessionTotalsRow(
    val miles: Double,
    val onlineMillis: Long,
)

/** Per-platform session totals (GROUP BY platform). */
data class PlatformSessionTotalsRow(
    val platform: String,
    val miles: Double,
    val onlineMillis: Long,
)
