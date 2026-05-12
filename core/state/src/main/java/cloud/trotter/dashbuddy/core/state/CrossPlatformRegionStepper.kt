package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.CrossPlatformRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Region 1 stepper — cross-platform aggregator.
 *
 * Derived, read-only from platform regions. Recomputed after Regions 2+ step.
 * The HUD's aggregate tab reads from this region.
 */
@Singleton
class CrossPlatformRegionStepper @Inject constructor() {

    fun step(
        prev: CrossPlatformRegion,
        prevPlatforms: Map<Platform, PlatformRegion>,
        nextPlatforms: Map<Platform, PlatformRegion>,
        obs: Observation,
    ): CrossPlatformRegion {
        val anyOnline = nextPlatforms.values.any { it.mode != Mode.Offline }
        val activeSessions = nextPlatforms.values.count { it.session != null }

        // Find most recent activity
        val mostRecent = nextPlatforms.entries
            .maxByOrNull { it.value.lastObservedAt }

        return prev.copy(
            anyPlatformOnline = anyOnline,
            activeSessionCount = activeSessions,
            mostRecentActivityAt = mostRecent?.value?.lastObservedAt ?: prev.mostRecentActivityAt,
            mostRecentActivityPlatform = mostRecent?.key,
            // totalsToday/Week/Lifetime are computed from DB aggregation,
            // not from in-memory state. They remain unchanged here.
        )
    }
}
