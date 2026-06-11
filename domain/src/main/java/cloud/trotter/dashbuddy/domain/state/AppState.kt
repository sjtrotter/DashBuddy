package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

/**
 * The complete state of the application across all regions.
 *
 * Read by the HUD, persisted as snapshots, and diffed for effect dispatch.
 *
 * @param correlationVersion Monotonically increasing; incremented on each
 *   accepted observation. Used as the join key between observation log
 *   entries and state snapshots.
 */
@Serializable
data class AppState(
    val regions: Regions = Regions(),
    /** Explicit (#366): 0 for the pre-restore initial state; obs-driven after. */
    val timestamp: Long = 0L,
    val correlationVersion: Long = 0L,
)

/**
 * Container for all statechart regions.
 *
 * @param flow Region 0 — ground-truth screen interpretation.
 * @param crossPlatform Region 1 — aggregator across all platform regions.
 * @param platforms Region 2+ — one per active platform.
 */
@Serializable
data class Regions(
    val flow: FlowRegion = FlowRegion(),
    val crossPlatform: CrossPlatformRegion = CrossPlatformRegion(),
    val platforms: Map<Platform, PlatformRegion> = emptyMap(),
)
