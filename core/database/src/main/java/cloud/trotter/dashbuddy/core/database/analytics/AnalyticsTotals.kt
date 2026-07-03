package cloud.trotter.dashbuddy.core.database.analytics

/**
 * Aggregate query-result projections for [AnalyticsDao] (#314). Plain POJOs Room
 * maps SUM/COUNT rows into — NOT entities, NOT persisted. They stay in
 * `:core:database` next to the DAO whose queries produce them; the read-side
 * repository (`:core:data`, PR3) folds economy in on top.
 */

/** Cross-platform delivery totals for a period. */
data class DeliveryTotalsRow(
    val pay: Double,
    val deliveries: Int,
    val jobs: Int,
)

/** Per-platform delivery totals (GROUP BY platform). */
data class PlatformDeliveryTotalsRow(
    val platform: String,
    val pay: Double,
    val deliveries: Int,
    val jobs: Int,
)

/** Per-store delivery totals (GROUP BY storeName) — per-store + future chain resolution (#159). */
data class StoreTotalsRow(
    val storeName: String?,
    val pay: Double,
    val deliveries: Int,
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
