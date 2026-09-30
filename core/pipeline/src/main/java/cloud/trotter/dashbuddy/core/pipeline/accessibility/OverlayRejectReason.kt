package cloud.trotter.dashbuddy.core.pipeline.accessibility

/**
 * Why a system-layer (`TYPE_SYSTEM`, non-PiP) window was NOT admitted as a platform offer-overlay
 * candidate (#1152 D2). Counted per reason in [cloud.trotter.dashbuddy.core.pipeline.PipelineStats]
 * (`overlayRejected{…}`) — enum names + counts only, no package or text. The checks run cheapest
 * first (size, then package), so each rejection names the FIRST failed check.
 */
enum class OverlayRejectReason {
    /** The display area is unknown (no metrics, no application window to measure) — fail closed. */
    NO_DISPLAY_AREA,

    /** Smaller than [cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource.MIN_OVERLAY_AREA_FRACTION] of the display (status bar, puck, heads-up, toast). */
    TOO_SMALL,

    /** Large enough, but its root could not be read, so its package cannot be verified — fail closed. */
    UNREADABLE,

    /** Large enough, but owned by a package no registry platform declares `offerOverlay` for (e.g. the notification shade). */
    NOT_OVERLAY_PLATFORM,

    /**
     * A memoized CANDIDATE whose freshly-fetched root names a different package (PR #1155 review
     * CC10) — the memo is corrected from the fresh root and the window is walked past.
     */
    PACKAGE_CHANGED,
}
