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

/**
 * THE session id the UI attributes to (#437) — derived from state, never a
 * second copy. BubbleManager used to mutate its own `activeDashId` from
 * StartSession/EndSession effects; crash recovery SUPPRESSES StartSession
 * (external effect), so a restored mid-dash process had a null dash id and
 * chat/cards attributed to nothing until the next DASH_START. Deriving from
 * the restored [AppState] makes recovery correct by construction
 * (principle 5 — the #356 SSOT family).
 *
 * Prefers the flow region's active platform; falls back to any platform with
 * a live session (single-platform alpha: at most one exists).
 */
fun AppState.activeSessionId(): String? {
    val active = regions.flow.activePlatform
        ?.let { regions.platforms[it]?.session?.sessionId }
    return active ?: regions.platforms.values.firstNotNullOfOrNull { it.session?.sessionId }
}
