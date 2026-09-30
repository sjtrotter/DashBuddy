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

    /** A non-enabled window is active and no application window is a candidate. */
    NO_CANDIDATE,

    /** The chosen root failed to map (the tree mapper threw or returned nothing). */
    MAP_FAILED,
}
