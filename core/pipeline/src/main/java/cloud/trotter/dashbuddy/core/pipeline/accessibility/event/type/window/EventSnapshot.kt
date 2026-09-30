package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window

import cloud.trotter.dashbuddy.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.state.Platform
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
 * 1. The ACTIVE ROOT (PR #1155 review FF1). When an overlay platform is enabled: ONE `getWindows()`;
 *    the single window flagged active ([AccessibilitySource.activeFromEnumeration]) is the active
 *    window and ITS root is the active root — the overlay decision then runs over that same list, so
 *    nothing is reconciled. When no window is flagged active (or its root is unreadable), or no overlay
 *    platform is enabled (a DoorDash-only dasher, #1148 H4 — no enumeration on this path), the one
 *    fallback: `getLiveNativeRoot()`, with NO overlay scan (the pre-#1152 behaviour). Null →
 *    `NO_ACTIVE_ROOT`.
 * 2. Its package enabled → FIRST (#1152 D5, PR #1155 review BB5): when the active window came from
 *    the enumeration, an ENABLED platform offer overlay in front above it
 *    ([AccessibilitySource.overlayFront]) is the frame for EVERY event while it is up —
 *    whichever window fired — so the covered window never interleaves with it (no R0 flap). It is
 *    on top by construction, so this never reads a window hidden beneath the active one; if the
 *    overlay was selected but fails to map, the frame is skipped `MAP_FAILED` (DD1 — never the covered window). Else
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
    // FF1: one enumeration decides "active" — only when an overlay could matter at all.
    val windows = if (Platform.overlayPackages.any(isEnabled)) {
        try {
            getWindows()
        } catch (_: Exception) {
            emptyList()
        }
    } else {
        null
    }
    val activeWindow = windows?.let { activeFromEnumeration(it) }
    val enumeratedRoot = activeWindow?.let { rootOf(it) }
    // The one fallback (FF2): no flagged window / unreadable root → rootInActiveWindow, no overlay scan.
    val activeRoot = enumeratedRoot ?: getLiveNativeRoot()
    if (activeRoot == null) {
        Timber.tag("Pipeline").v("🚫 Skip: no active root (event window=%d pkg=%s)", windowId, eventPackage)
        return EventSnapshot.Skipped(ForegroundSkipReason.NO_ACTIVE_ROOT)
    }
    val activePkg = activeRoot.packageName?.toString()
    val snapshot = if (isEnabled(activePkg)) {
        val overlay = if (windows != null && activeWindow != null && enumeratedRoot != null) {
            onOverlayScan()
            when (val scan = overlayFront(windows, activeWindow, isEnabled)) {
                AccessibilitySource.OverlayScan.None -> null
                is AccessibilitySource.OverlayScan.Overlay -> scan.located
                is AccessibilitySource.OverlayScan.Refused -> {
                    Timber.tag("Pipeline").v("🚫 Skip: overlay scan refused %s (event window=%d)", scan.reason, windowId)
                    return EventSnapshot.Skipped(scan.reason)
                }
            }
        } else {
            null
        }
        if (overlay != null) {
            // PR #1155 review DD1 (reverses BB9): the walk SELECTED an overlay — a map failure does
            // not prove it left, and reading the covered window beneath would re-open the interleave.
            // The frame is skipped `MAP_FAILED` (retried on the next); "no overlay selected" is the
            // only case that reads the active root.
            getWindowSnapshot(overlay.window, overlay.root, overlay.totalWindowCount)
        } else {
            getCurrentRootSnapshot(activeRoot)
        }
    } else {
        // Reuse THIS enumeration when there is one (FF1); else enumerate as #1148 did.
        val front = if (windows != null) foregroundWindow(windows, isEnabled) else foregroundWindow(isEnabled)
        when (front) {
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
