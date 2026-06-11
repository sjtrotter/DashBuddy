package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

/**
 * Region 1 — cross-platform aggregator.
 *
 * Derived, read-only from platform regions. Recomputed after Regions 2+ step.
 * The HUD's aggregate tab reads from this region.
 */
@Serializable
data class CrossPlatformRegion(
    val anyPlatformOnline: Boolean = false,
    val activeSessionCount: Int = 0,
    val totalsToday: PeriodTotals = PeriodTotals.EMPTY,
    val totalsThisWeek: PeriodTotals = PeriodTotals.EMPTY,
    val totalsLifetime: PeriodTotals = PeriodTotals.EMPTY,
    val mostRecentActivityAt: Long = 0,
    val mostRecentActivityPlatform: Platform? = null,
)

/**
 * Aggregated totals for a time period across all platforms.
 */
@Serializable
data class PeriodTotals(
    val earnings: Double = 0.0,
    val miles: Double = 0.0,
    val deliveries: Int = 0,
    val jobs: Int = 0,
    val onlineDuration: Long = 0L,
) {
    companion object {
        val EMPTY = PeriodTotals()
    }
}
