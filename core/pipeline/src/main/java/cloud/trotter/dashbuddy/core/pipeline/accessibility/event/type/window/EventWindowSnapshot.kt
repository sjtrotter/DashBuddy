package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window

import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.state.Platform
import timber.log.Timber

/**
 * The one snapshot-resolution order for the event-driven window pipelines (content + state
 * changes, #1148 D4), so the two can never drift:
 *
 * 1. If the event names a window (`windowId >= 0`): pre-map read of THAT window's package
 *    ([AccessibilitySource.getWindowPackage], root only — the #435 item-3 check-before-map); a
 *    non-target package is skipped without mapping; a target one is snapshotted from the event's
 *    own window ([AccessibilitySource.getWindowSnapshot]). This is what lets a DoorDash content
 *    change through while our bubble is the ACTIVE window.
 * 2. If the window is unavailable (no id, gone, no root, map failure) → FALLBACK to the
 *    active-root path: [AccessibilitySource.getActiveWindowPackage] pre-map check, then
 *    [AccessibilitySource.getCurrentRootSnapshot].
 * 3. Post-map re-check of the snapshot's OWN package against [Platform.watchedPackages] (a root
 *    can swap between the package read and the map) — the #4 self-recognition guard: a snapshot
 *    is always attributed to the window's real package, never to the triggering event.
 */
internal fun AccessibilitySource.snapshotForEventWindow(
    windowId: Int,
    eventPackage: String?,
): AccessibilitySource.RootSnapshot? {
    val snapshot = if (windowId >= 0) {
        val windowPkg = getWindowPackage(windowId)
        if (windowPkg != null && windowPkg !in Platform.watchedPackages) {
            Timber.v(
                "🚫 Skip event window %d (pre-map): non-target pkg=%s (event pkg=%s)",
                windowId, windowPkg, eventPackage,
            )
            return null
        }
        (if (windowPkg != null) getWindowSnapshot(windowId) else null)
            ?: activeRootSnapshot(eventPackage)
    } else {
        activeRootSnapshot(eventPackage)
    } ?: return null

    if (snapshot.packageName !in Platform.watchedPackages) {
        Timber.v(
            "🚫 Skip window: non-target pkg=%s (event pkg=%s)",
            snapshot.packageName, eventPackage,
        )
        return null
    }
    return snapshot
}

/** The pre-#1148 active-root path, kept as the fallback when the event's window is unavailable. */
private fun AccessibilitySource.activeRootSnapshot(eventPackage: String?): AccessibilitySource.RootSnapshot? {
    val activePkg = getActiveWindowPackage()
    if (activePkg !in Platform.watchedPackages) {
        Timber.v(
            "🚫 Skip active window (pre-map): non-target pkg=%s (event pkg=%s)",
            activePkg, eventPackage,
        )
        return null
    }
    return getCurrentRootSnapshot()
}
