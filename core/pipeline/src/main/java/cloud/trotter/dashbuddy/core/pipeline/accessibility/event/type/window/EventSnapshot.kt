package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window

import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.state.Platform
import timber.log.Timber

/**
 * The one snapshot-resolution order for the event-driven window pipelines (content + state
 * changes, #1148 D4 as reworked by review F1), so the two can never drift. The triggering event
 * is only a TRIGGER — its window is never the snapshot source, because a hidden activity under a
 * watched modal sheet keeps firing content changes with ITS window id, and snapshotting it would
 * interleave obscured frames with the sheet's and flap R0.
 *
 * 1. Active window is WATCHED (`getActiveWindowPackage`, root only — the #435 item-3 pre-map
 *    read) → the active root is the ground truth ([AccessibilitySource.getCurrentRootSnapshot]),
 *    a watched sheet over a watched activity included. This is the pre-#1148 behaviour.
 * 2. Active window is NOT watched (our bubble, the launcher, system UI) → the TOPMOST watched
 *    application window ([AccessibilitySource.topmostWindow], one enumeration, one root fetch per
 *    candidate), mapped from its already-fetched root. Previously such frames were dropped.
 * 3. No watched application window → null (VERBOSE skip), nothing mapped.
 * 4. Post-map re-check of the snapshot's OWN package against [Platform.watchedPackages] (a root
 *    can swap between the package read and the map) — the #4 self-recognition guard.
 */
internal fun AccessibilitySource.snapshotForEvent(
    windowId: Int,
    eventPackage: String?,
): AccessibilitySource.RootSnapshot? {
    val activePkg = getActiveWindowPackage()
    val snapshot = if (activePkg in Platform.watchedPackages) {
        getCurrentRootSnapshot()
    } else {
        val top = topmostWindow { it in Platform.watchedPackages }
        if (top == null) {
            Timber.v(
                "🚫 Skip (pre-map): active pkg=%s not watched and no watched application window (event window=%d pkg=%s)",
                activePkg, windowId, eventPackage,
            )
            return null
        }
        getWindowSnapshot(top.window, top.root, top.totalWindowCount)
    } ?: return null

    if (snapshot.packageName !in Platform.watchedPackages) {
        Timber.v(
            "🚫 Skip window: non-target pkg=%s (event window=%d pkg=%s)",
            snapshot.packageName, windowId, eventPackage,
        )
        return null
    }
    return snapshot
}
