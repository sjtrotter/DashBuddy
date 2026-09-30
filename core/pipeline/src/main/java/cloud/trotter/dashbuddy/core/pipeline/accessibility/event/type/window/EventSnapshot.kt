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
            // PR #1155 review HH4: a THROWING enumeration is not an empty list — no enumeration to
            // reuse; the not-enabled branch re-enumerates inside foregroundWindow and reports
            // FRONT_UNREADABLE on failure, as #1148 did.
            null
        }
    } else {
        null
    }
    val step: Step = if (windows == null) {
        preOverlayRead(getLiveNativeRoot(), null, isEnabled, windowId, eventPackage)
    } else {
        resolveOnList(windows, isEnabled, windowId, eventPackage)
    }
    val snapshot = when (step) {
        is Step.Skip -> return EventSnapshot.Skipped(step.reason)
        is Step.Frame -> step.snapshot
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

/** One resolution step's outcome: a (possibly failed-to-map) frame, or a counted skip. */
private sealed interface Step {
    data class Frame(val snapshot: AccessibilitySource.RootSnapshot?) : Step
    data class Skip(val reason: ForegroundSkipReason) : Step
}

/**
 * Resolution over ONE enumeration (FF1/HH1). The active window comes from [AccessibilitySource.resolveActive];
 * with no single flagged window (JJ2) the native root's OWN window is located in the list by its id
 * and resolved as the active one; only when the native root is not provably in the list is the
 * pre-#1152 read taken.
 */
private fun AccessibilitySource.resolveOnList(
    windows: List<android.view.accessibility.AccessibilityWindowInfo>,
    isEnabled: (String?) -> Boolean,
    windowId: Int,
    eventPackage: String?,
): Step {
    var resolution = resolveActive(windows, isEnabled)
    if (resolution is AccessibilitySource.ActiveWindow.Unknown && resolution.window == null) {
        // PR #1155 review JJ2: zero or ≥ 2 flagged windows — the native root's own window, found BY ID
        // in this list, is the active window (so an overlay above it is still scanned). Not provably
        // in the list → the pre-#1152 read.
        val native = getLiveNativeRoot()
        val nativeWindow = native?.windowId?.takeIf { it >= 0 }?.let { id -> windows.firstOrNull { it.id == id } }
            ?: return preOverlayRead(native, windows, isEnabled, windowId, eventPackage)
        resolution = classify(nativeWindow, native, isEnabled)
    }
    return when (val r = resolution) {
        is AccessibilitySource.ActiveWindow.NotEnabled -> frontRead(windows, isEnabled, windowId, eventPackage)
        is AccessibilitySource.ActiveWindow.Enabled -> scanThen(windows, r.window, isEnabled, windowId) {
            // HH5: the window object is in hand — the ONE window builder maps it.
            Step.Frame(getWindowSnapshot(r.window, r.root, windows.size))
        }
        is AccessibilitySource.ActiveWindow.Unknown -> {
            val active = r.window ?: return preOverlayRead(getLiveNativeRoot(), windows, isEnabled, windowId, eventPackage)
            scanThen(windows, active, isEnabled, windowId) {
                // II1/JJ1: the flagged window's own root was unreadable — ONE rootInActiveWindow read
                // stands in only if it provably IS that window (same non-negative id); it then follows
                // exactly the resolution its package calls for. Anything else → skip (the topology
                // path emits nothing for the same case).
                val native = getLiveNativeRoot()
                if (native == null || native.windowId < 0 || native.windowId != active.id) {
                    Timber.tag("Pipeline").v("🚫 Skip: active window root unreadable, no overlay in front (event window=%d)", windowId)
                    return@scanThen Step.Skip(ForegroundSkipReason.FRONT_UNREADABLE)
                }
                when (val matched = classify(active, native, isEnabled)) {
                    is AccessibilitySource.ActiveWindow.Enabled -> Step.Frame(getWindowSnapshot(active, native, windows.size))
                    is AccessibilitySource.ActiveWindow.NotEnabled -> frontRead(windows, isEnabled, windowId, eventPackage)
                    is AccessibilitySource.ActiveWindow.Unknown -> Step.Skip(ForegroundSkipReason.FRONT_UNREADABLE)
                }
            }
        }
    }
}

/** [resolveActive]'s classification of an ALREADY-fetched root of [window] (JJ1/JJ2). */
private fun classify(
    window: android.view.accessibility.AccessibilityWindowInfo,
    root: android.view.accessibility.AccessibilityNodeInfo,
    isEnabled: (String?) -> Boolean,
): AccessibilitySource.ActiveWindow {
    val pkg = root.packageName?.toString() ?: return AccessibilitySource.ActiveWindow.Unknown(window)
    return if (isEnabled(pkg)) {
        AccessibilitySource.ActiveWindow.Enabled(window, root)
    } else {
        AccessibilitySource.ActiveWindow.NotEnabled(window, root)
    }
}

/**
 * HH1: the overlay scan runs off the active WINDOW's layer (it never needs the root). An enabled
 * overlay in front is the frame; a refusal skips; no overlay → [onNone].
 */
private inline fun AccessibilitySource.scanThen(
    windows: List<android.view.accessibility.AccessibilityWindowInfo>,
    active: android.view.accessibility.AccessibilityWindowInfo,
    noinline isEnabled: (String?) -> Boolean,
    windowId: Int,
    onNone: () -> Step,
): Step {
    onOverlayScan()
    return when (val scan = walk.overlayFront(windows, active, isEnabled)) {
        is AccessibilitySource.OverlayScan.Refused -> {
            Timber.tag("Pipeline").v("🚫 Skip: overlay scan refused %s (event window=%d)", scan.reason, windowId)
            Step.Skip(scan.reason)
        }
        // PR #1155 review DD1 (reverses BB9): the walk SELECTED an overlay — a map failure does not
        // prove it left; the frame is skipped `MAP_FAILED` (retried on the next), never the window beneath.
        is AccessibilitySource.OverlayScan.Overlay ->
            Step.Frame(getWindowSnapshot(scan.located.window, scan.located.root, scan.located.totalWindowCount))
        AccessibilitySource.OverlayScan.None -> onNone()
    }
}

/** Our bubble / the launcher / a disabled platform is active → the window in front (#1148 D4). */
private fun AccessibilitySource.frontRead(
    windows: List<android.view.accessibility.AccessibilityWindowInfo>?,
    isEnabled: (String?) -> Boolean,
    windowId: Int,
    eventPackage: String?,
): Step {
    // Reuse THIS enumeration when there is one (FF1); else enumerate as #1148 did.
    val front = if (windows != null) foregroundWindow(windows, isEnabled) else foregroundWindow(isEnabled)
    return when (front) {
        is AccessibilitySource.Foreground.Refused -> {
            Timber.tag("Pipeline").v(
                "🚫 Skip (pre-map): active window not enabled, front refused %s (event window=%d pkg=%s)",
                front.reason, windowId, eventPackage,
            )
            Step.Skip(front.reason)
        }
        is AccessibilitySource.Foreground.Found ->
            Step.Frame(getWindowSnapshot(front.located.window, front.located.root, front.located.totalWindowCount))
    }
}

/**
 * The pre-#1152 read over an already-fetched [activeRoot]: no overlay platform enabled (#1148 H4 — no
 * enumeration), a throwing enumeration (HH4), or a native root that is not provably in the list (JJ2).
 */
private fun AccessibilitySource.preOverlayRead(
    activeRoot: android.view.accessibility.AccessibilityNodeInfo?,
    windows: List<android.view.accessibility.AccessibilityWindowInfo>?,
    isEnabled: (String?) -> Boolean,
    windowId: Int,
    eventPackage: String?,
): Step {
    if (activeRoot == null) {
        Timber.tag("Pipeline").v("🚫 Skip: no active root (event window=%d pkg=%s)", windowId, eventPackage)
        return Step.Skip(ForegroundSkipReason.NO_ACTIVE_ROOT)
    }
    return if (isEnabled(activeRoot.packageName?.toString())) {
        Step.Frame(getCurrentRootSnapshot(activeRoot))
    } else {
        frontRead(windows, isEnabled, windowId, eventPackage)
    }
}
