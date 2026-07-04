package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

/**
 * Region 1 — cross-platform aggregator.
 *
 * Derived, read-only from platform regions. Recomputed after Regions 2+ step.
 * The HUD's aggregate tab reads from this region.
 *
 * Period totals (today/week/lifetime) are **not** held here: they are history, and
 * history lives on the read side (`AnalyticsRepository`, #314 §3), never in a pure
 * stepper — a reducer must not read the DB. The old dead `totals*` fields (the
 * stepper never populated them) were deleted with #314 PR3; `PeriodTotals` moved to
 * `domain/analytics/` as the read-side DTO.
 */
@Serializable
data class CrossPlatformRegion(
    val anyPlatformOnline: Boolean = false,
    val activeSessionCount: Int = 0,
    val mostRecentActivityAt: Long = 0,
    val mostRecentActivityPlatform: Platform? = null,
)
