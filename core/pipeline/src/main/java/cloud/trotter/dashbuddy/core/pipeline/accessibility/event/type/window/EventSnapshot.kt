package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window

import cloud.trotter.dashbuddy.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import timber.log.Timber

/** The resolver's verdict (#1148 review H3): a frame, or a counted reason for none. */
internal sealed interface EventSnapshot {
    data class Resolved(val snapshot: AccessibilitySource.RootSnapshot) : EventSnapshot
    data class Skipped(val reason: ForegroundSkipReason) : EventSnapshot
}

/**
 * The one snapshot-resolution order for the event-driven window pipelines (content + state
 * changes, #1148 D4 as reworked by review F1/G5/H1–H3), so the two can never drift. The triggering
 * event is only a TRIGGER — its window is never the snapshot source, because a hidden activity
 * under a watched modal sheet keeps firing content changes with ITS window id.
 *
 * [isEnabled] is the ENABLED-platform package set (`PlatformPreferences.enabledPackages`), not the
 * static watched set: a disabled platform's window is never read.
 *
 * 1. ONE `getLiveNativeRoot()`. Null → `NO_ACTIVE_ROOT` (the pre-#1148 behaviour; never enumerate
 *    as a fallback).
 * 2. Its package enabled → map THAT root ([AccessibilitySource.getCurrentRootSnapshot] over the
 *    already-fetched node): the active enabled window is the ground truth, a sheet over its
 *    activity included.
 * 3. Otherwise (our bubble, the launcher, system UI active) → [AccessibilitySource.foregroundWindow]:
 *    readable-top-or-refuse over application windows; a refusal carries its reason.
 * 4. A root that fails to map → `MAP_FAILED`.
 * 5. A post-map check of the snapshot's package against [isEnabled]. Both builders derive
 *    `packageName` from the SAME already-fetched root the gate read, so this cannot catch a
 *    read-to-read swap — it only fires on a programming error. Kept as a belt-and-braces invariant
 *    (review H5) guarding the #4 self-recognition rule.
 *
 * Every [EventSnapshot.Skipped] is counted by the caller (`PipelineStats.onForegroundSkip`).
 */
internal fun AccessibilitySource.snapshotForEvent(
    windowId: Int,
    eventPackage: String?,
    isEnabled: (String?) -> Boolean,
): EventSnapshot {
    val activeRoot = getLiveNativeRoot()
    if (activeRoot == null) {
        Timber.tag("Pipeline").v("🚫 Skip: no active root (event window=%d pkg=%s)", windowId, eventPackage)
        return EventSnapshot.Skipped(ForegroundSkipReason.NO_ACTIVE_ROOT)
    }
    val activePkg = activeRoot.packageName?.toString()
    val snapshot = if (isEnabled(activePkg)) {
        getCurrentRootSnapshot(activeRoot)
    } else {
        when (val front = foregroundWindow(isEnabled)) {
            is AccessibilitySource.Foreground.Refused -> {
                Timber.tag("Pipeline").v(
                    "🚫 Skip (pre-map): active pkg=%s not enabled, front refused %s (event window=%d pkg=%s)",
                    activePkg, front.reason, windowId, eventPackage,
                )
                return EventSnapshot.Skipped(front.reason)
            }
            is AccessibilitySource.Foreground.Found ->
                getWindowSnapshot(front.located.window, front.located.root, front.located.totalWindowCount)
        }
    } ?: return EventSnapshot.Skipped(ForegroundSkipReason.MAP_FAILED)

    if (!isEnabled(snapshot.packageName)) { // invariant — see step 5 (H5)
        // A defended invariant fired (principle 7 → WARN, counts/packages only, no UI text).
        Timber.tag("Pipeline").w(
            "Post-map package mismatch: snapshot pkg=%s failed the gate its own root passed (event window=%d) — dropping",
            snapshot.packageName, windowId,
        )
        return EventSnapshot.Skipped(ForegroundSkipReason.POST_MAP_MISMATCH)
    }
    return EventSnapshot.Resolved(snapshot)
}
