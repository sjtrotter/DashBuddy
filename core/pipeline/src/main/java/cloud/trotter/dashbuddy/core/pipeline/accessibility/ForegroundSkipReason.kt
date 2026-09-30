package cloud.trotter.dashbuddy.core.pipeline.accessibility

/**
 * Why the event-driven window resolver produced no frame (#1148 review H3). Counted per reason in
 * [cloud.trotter.dashbuddy.core.pipeline.PipelineStats] (`foregroundSkip{…}`) so the new gate
 * decisions stay visible in the periodic summary — counts only, no package or text.
 */
enum class ForegroundSkipReason {
    /** No active root at all (the pre-#1148 skip). */
    NO_ACTIVE_ROOT,

    /** A non-enabled window is in front (another app, the launcher, a disabled platform). */
    FRONT_NOT_ENABLED,

    /** The window in front could not be read (null root) — fail closed. */
    FRONT_UNREADABLE,

    /** A non-enabled window is active and no window (application or platform offer overlay, #1152) is a candidate. */
    NO_CANDIDATE,

    /**
     * The mapped snapshot's package disagrees with the package the gate read from the SAME root —
     * a programming error, never a race (PR #1150 review round 4). Counted on its own so the
     * invariant firing is visible in the census instead of hiding under [FRONT_NOT_ENABLED].
     */
    POST_MAP_MISMATCH,

    /**
     * The front-window walk ran out of its root-fetch budget (PR #1155 review CC5,
     * `AccessibilitySource.MAX_SCAN_ROOT_FETCHES`) before it could verify what is on top — refused,
     * never fallen through.
     */
    SCAN_BUDGET,

    /**
     * The display area is unknown, so no system-layer window on top can be verified as (not) an offer
     * overlay (PR #1155 review DD8) — the bubble path refuses rather than walk past it.
     */
    NO_DISPLAY_AREA,

    /** The chosen root failed to map (the tree mapper threw or returned nothing). */
    MAP_FAILED,
}
