package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window

import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import timber.log.Timber

/**
 * The one snapshot-resolution order for the event-driven window pipelines (content + state
 * changes, #1148 D4 as reworked by review F1/G5), so the two can never drift. The triggering event
 * is only a TRIGGER — its window is never the snapshot source, because a hidden activity under a
 * watched modal sheet keeps firing content changes with ITS window id.
 *
 * [isEnabled] is the ENABLED-platform package set (`PlatformPreferences.enabledPackages`), not the
 * static watched set: a disabled platform's window is never read.
 *
 * 1. ONE `getLiveNativeRoot()`. Null → skip the frame (the pre-#1148 behaviour; never enumerate as
 *    a fallback).
 * 2. Its package enabled → map THAT root ([AccessibilitySource.getCurrentRootSnapshot] over the
 *    already-fetched node): the active enabled window is the ground truth, a sheet over its
 *    activity included.
 * 3. Otherwise (our bubble, the launcher, system UI active) → [AccessibilitySource.foregroundWindow]:
 *    readable-top-or-refuse over application windows + readable enabled system overlays. None → skip.
 * 4. Post-map re-check of the snapshot's OWN package against [isEnabled] — the #4 guard.
 */
internal fun AccessibilitySource.snapshotForEvent(
    windowId: Int,
    eventPackage: String?,
    isEnabled: (String?) -> Boolean,
): AccessibilitySource.RootSnapshot? {
    val activeRoot = getLiveNativeRoot()
    if (activeRoot == null) {
        Timber.v("🚫 Skip: no active root (event window=%d pkg=%s)", windowId, eventPackage)
        return null
    }
    val activePkg = activeRoot.packageName?.toString()
    val snapshot = if (isEnabled(activePkg)) {
        getCurrentRootSnapshot(activeRoot)
    } else {
        val front = foregroundWindow(isEnabled)
        if (front == null) {
            Timber.v(
                "🚫 Skip (pre-map): active pkg=%s not enabled and no readable enabled window in front (event window=%d pkg=%s)",
                activePkg, windowId, eventPackage,
            )
            return null
        }
        getWindowSnapshot(front.window, front.root, front.totalWindowCount)
    } ?: return null

    if (!isEnabled(snapshot.packageName)) {
        Timber.v(
            "🚫 Skip window: non-target pkg=%s (event window=%d pkg=%s)",
            snapshot.packageName, windowId, eventPackage,
        )
        return null
    }
    return snapshot
}
