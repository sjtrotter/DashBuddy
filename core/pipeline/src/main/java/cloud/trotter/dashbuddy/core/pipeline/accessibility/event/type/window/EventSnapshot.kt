package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window

import cloud.trotter.dashbuddy.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.state.Platform
import timber.log.Timber

/** The resolver's verdict (#1148 review H3): a frame, or a counted reason for none. */
internal sealed interface EventSnapshot {
    /** [viaOverlay]: read from a platform offer overlay (#1152) — the caller counts it. */
    data class Resolved(
        val snapshot: AccessibilitySource.RootSnapshot,
        val viaOverlay: Boolean = false,
    ) : EventSnapshot
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
 * 2. Its package enabled → FIRST (#1152 D5, PR #1155 review BB5): when any overlay platform is
 *    enabled, an ENABLED platform offer overlay drawn ABOVE the active window
 *    ([AccessibilitySource.overlayAboveActive]) is the frame for EVERY event while it is up —
 *    whichever window fired — so the covered window never interleaves with it (no R0 flap). It is
 *    on top by construction, so this never reads a window hidden beneath the active one; if the
 *    overlay fails to map (tearing down mid-walk), the active root is read instead (BB9). Else
 *    map THAT root ([AccessibilitySource.getCurrentRootSnapshot] over the
 *    already-fetched node): the active enabled window is the ground truth, a sheet over its
 *    activity included.
 * 3. Otherwise (our bubble, the launcher, system UI active) → [AccessibilitySource.foregroundWindow]:
 *    readable-top-or-refuse over application windows AND platform offer overlays (#1152 D4 — so an
 *    overlay over a non-enabled active window is reached HERE, under the fail-closed top rule);
 *    a refusal carries its reason.
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
    var viaOverlay = false
    val snapshot = if (isEnabled(activePkg)) {
        // BB5: the check is independent of which window fired. Its cheap pre-gate: no enumeration at
        // all unless some overlay platform is enabled (a DoorDash-only dasher pays nothing).
        val overlay = if (Platform.overlayPackages.any(isEnabled)) {
            overlayAboveActive(activeRoot.windowId, isEnabled)
        } else {
            null
        }
        // BB9: an overlay that fails to map (the card tearing down mid-walk) falls back to the
        // active root — the shipped ground truth — rather than dropping the frame.
        val overlaySnapshot = overlay?.let { getWindowSnapshot(it.window, it.root, it.totalWindowCount) }
        if (overlaySnapshot != null) {
            viaOverlay = true
            overlaySnapshot
        } else {
            getCurrentRootSnapshot(activeRoot)
        }
    } else {
        when (val front = foregroundWindow(isEnabled)) {
            is AccessibilitySource.Foreground.Refused -> {
                Timber.tag("Pipeline").v(
                    "🚫 Skip (pre-map): active pkg=%s not enabled, front refused %s (event window=%d pkg=%s)",
                    activePkg, front.reason, windowId, eventPackage,
                )
                return EventSnapshot.Skipped(front.reason)
            }
            is AccessibilitySource.Foreground.Found -> {
                viaOverlay = front.located.isOverlay
                getWindowSnapshot(front.located.window, front.located.root, front.located.totalWindowCount)
            }
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
    return EventSnapshot.Resolved(snapshot, viaOverlay)
}
