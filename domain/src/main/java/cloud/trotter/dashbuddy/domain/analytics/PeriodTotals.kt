package cloud.trotter.dashbuddy.domain.analytics

import kotlinx.serialization.Serializable

/**
 * Raw aggregated counts/sums for a period across all platforms (#314) — the
 * read-side DTO the [AnalyticsRepository][cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository]
 * assembles from the durable read-model tables. Economics ([PeriodEconomics])
 * wraps this with frozen net and reported-authoritative gross.
 *
 * Formerly three dead fields on `CrossPlatformRegion` (the pure state-machine
 * reducer never populated them — totals are history, and history lives on the
 * read side, never in a pure stepper; #314 §3). It kept its `@Serializable`
 * annotation on the move so any legacy usage stays decode-compatible.
 *
 * [earnings] is *delivered* pay (Σ realized delivery pay). The platform's own
 * reported all-pay total — which additionally covers challenge bonuses and
 * adjustments — is carried separately as [PeriodEconomics.grossEarnings].
 */
@Serializable
data class PeriodTotals(
    /** Σ realized delivery pay bucketed by completion time. */
    val earnings: Double = 0.0,
    /** Σ session odometer delta (floored per session). */
    val miles: Double = 0.0,
    /** Count of completed deliveries. */
    val deliveries: Int = 0,
    /** Count of distinct delivered jobs. */
    val jobs: Int = 0,
    /** Σ session online duration, millis. */
    val onlineDuration: Long = 0L,
) {
    companion object {
        val EMPTY = PeriodTotals()
    }
}
