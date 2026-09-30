package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window

import cloud.trotter.dashbuddy.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.mapWindow
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
 * 1. The ACTIVE WINDOW. No overlay platform enabled (a DoorDash-only dasher, #1148 H4) or a throwing
 *    enumeration (PR #1155 review HH4) → the pre-#1152 read: `rootInActiveWindow`; null →
 *    `NO_ACTIVE_ROOT`; enabled → map it; else step 3. Otherwise ONE `getWindows()` and the three-valued
 *    [AccessibilitySource.resolveActive] (review HH1, shared with the topology path): **Enabled /
 *    NotEnabled** — the single flagged window with a readable, packaged FRESH root; **Unknown(window)**
 *    — flagged but unreadable; **Unknown(none)** — zero or ≥ 2 flagged, in which case the native root's
 *    OWN window, located in the list by its id, is resolved as the active one (review JJ2), and only a
 *    native root not provably in the list takes the pre-#1152 read.
 * 2. Enabled or Unknown(window) → the overlay scan off the active WINDOW's layer
 *    ([FrontWindowWalk.overlayFront], #1152 D5 / review BB5): an ENABLED platform offer overlay in
 *    front is the frame for EVERY event while it is up — whichever window fired — so the covered
 *    window never interleaves with it; a selected overlay that fails to map skips `MAP_FAILED` (DD1).
 *    No overlay: Enabled → the active window through the ONE window builder (HH5); Unknown(window) →
 *    a `rootInActiveWindow` that provably IS that window (same non-negative id) follows the resolution
 *    its package calls for — enabled → mapped, not enabled → step 3 (reviews II1/JJ1); anything else
 *    skips `FRONT_UNREADABLE`.
 * 3. NotEnabled (our bubble, the launcher, a disabled platform) → [AccessibilitySource.foregroundWindow]
 *    over the same list: readable-top-or-refuse over application windows AND platform offer overlays
 *    (#1152 D4); a refusal carries its reason.
 * 4. A root that fails to map → `MAP_FAILED`.
 * 5. A post-map check of the snapshot's package against [isEnabled]. Both builders derive
 *    `packageName` from the SAME already-fetched root the gate read, so this cannot catch a
 *    read-to-read swap — it only fires on a programming error. Kept as a belt-and-braces invariant
 *    (review H5) guarding the #4 self-recognition rule.
 *
 * Every [EventSnapshot.Skipped] is counted by the caller (`PipelineStats.onForegroundSkip`).
 *
 * [FrontWindowWalk]: `cloud.trotter.dashbuddy.core.pipeline.accessibility.input.FrontWindowWalk`.
 */
internal fun AccessibilitySource.snapshotForEvent(
    windowId: Int,
    eventPackage: String?,
    isEnabled: (String?) -> Boolean,
): EventSnapshot {
    // KK1: the verdict-cache generation is read BEFORE the enumeration and passed down.
    val gen = generation
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
        resolveOnList(windows, gen, isEnabled, windowId, eventPackage)
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
 * Resolution over ONE enumeration (FF1/HH1/KK2). The active WINDOW is the single flagged one; with none
 * or ≥ 2 flagged (JJ2) the native root's OWN window, located in the list by its id — only a native root
 * not provably in the list takes the pre-#1152 read. The overlay scan runs FIRST, off that window's
 * layer (KK2: no active root is fetched while an enabled overlay is up); only when it finds nothing is
 * the active window classified ([AccessibilitySource.resolveActive] / [AccessibilitySource.classify]).
 */
private fun AccessibilitySource.resolveOnList(
    windows: List<android.view.accessibility.AccessibilityWindowInfo>,
    gen: Long,
    isEnabled: (String?) -> Boolean,
    windowId: Int,
    eventPackage: String?,
): Step {
    var nativeForWindow: android.view.accessibility.AccessibilityNodeInfo? = null
    val active = activeFromEnumeration(windows).single ?: run {
        // PR #1155 review JJ2: zero or ≥ 2 flagged windows — the native root's own window, found BY ID.
        val native = getLiveNativeRoot()
        val w = native?.windowId?.takeIf { it >= 0 }?.let { id -> windows.firstOrNull { it.id == id } }
            ?: return preOverlayRead(native, windows, isEnabled, windowId, eventPackage)
        nativeForWindow = native
        w
    }
    val provenNative = nativeForWindow
    return scanThen(windows, active, gen, isEnabled, windowId) {
        val resolution = provenNative?.let { classify(active, it, isEnabled, gen) }
            ?: resolveActive(active, isEnabled, gen)
        when (resolution) {
            // HH5/KK4: the window object is in hand — the ONE window builder maps it; a focused offer
            // overlay (the card IS the active window) is counted from its own probe verdict.
            is AccessibilitySource.ActiveWindow.Enabled ->
                Step.Frame(mapWindow(active, resolution.root, windows.size, activeIsOverlay(active, gen)))
            is AccessibilitySource.ActiveWindow.NotEnabled -> frontRead(windows, gen, isEnabled, windowId, eventPackage)
            is AccessibilitySource.ActiveWindow.Unknown -> {
                // II1/JJ1: the active window's own root was unreadable — ONE rootInActiveWindow read
                // stands in only if it provably IS that window (same non-negative id); it then follows
                // exactly the resolution its package calls for. Anything else → skip (the topology
                // path emits nothing for the same case).
                val native = getLiveNativeRoot()
                if (native == null || native.windowId < 0 || native.windowId != active.id) {
                    Timber.tag("Pipeline").v("🚫 Skip: active window root unreadable, no overlay in front (event window=%d)", windowId)
                    Step.Skip(ForegroundSkipReason.FRONT_UNREADABLE)
                } else {
                    when (val matched = classify(active, native, isEnabled, gen)) {
                        is AccessibilitySource.ActiveWindow.Enabled ->
                            Step.Frame(mapWindow(active, matched.root, windows.size, activeIsOverlay(active, gen)))
                        is AccessibilitySource.ActiveWindow.NotEnabled -> frontRead(windows, gen, isEnabled, windowId, eventPackage)
                        is AccessibilitySource.ActiveWindow.Unknown -> Step.Skip(ForegroundSkipReason.FRONT_UNREADABLE)
                    }
                }
            }
        }
    }
}

/**
 * HH1: the overlay scan runs off the active WINDOW's layer (it never needs the root). An enabled
 * overlay in front is the frame; a refusal skips; no overlay → [onNone].
 */
private inline fun AccessibilitySource.scanThen(
    windows: List<android.view.accessibility.AccessibilityWindowInfo>,
    active: android.view.accessibility.AccessibilityWindowInfo,
    gen: Long,
    noinline isEnabled: (String?) -> Boolean,
    windowId: Int,
    onNone: () -> Step,
): Step {
    onOverlayScan()
    return when (val scan = walk.overlayFront(windows, active, isEnabled, gen = gen)) {
        is AccessibilitySource.OverlayScan.Refused -> {
            Timber.tag("Pipeline").v("🚫 Skip: overlay scan refused %s (event window=%d)", scan.reason, windowId)
            Step.Skip(scan.reason)
        }
        // PR #1155 review DD1 (reverses BB9): the walk SELECTED an overlay — a map failure does not
        // prove it left; the frame is skipped `MAP_FAILED` (retried on the next), never the window beneath.
        is AccessibilitySource.OverlayScan.Overlay ->
            Step.Frame(mapWindow(scan.located))
        AccessibilitySource.OverlayScan.None -> onNone()
    }
}

/** Our bubble / the launcher / a disabled platform is active → the window in front (#1148 D4). */
private fun AccessibilitySource.frontRead(
    windows: List<android.view.accessibility.AccessibilityWindowInfo>?,
    gen: Long,
    isEnabled: (String?) -> Boolean,
    windowId: Int,
    eventPackage: String?,
): Step {
    // Reuse THIS enumeration when there is one (FF1); else enumerate as #1148 did.
    val front = if (windows != null) foregroundWindow(windows, isEnabled, gen = gen) else foregroundWindow(isEnabled)
    return when (front) {
        is AccessibilitySource.Foreground.Refused -> {
            Timber.tag("Pipeline").v(
                "🚫 Skip (pre-map): active window not enabled, front refused %s (event window=%d pkg=%s)",
                front.reason, windowId, eventPackage,
            )
            Step.Skip(front.reason)
        }
        is AccessibilitySource.Foreground.Found ->
            Step.Frame(mapWindow(front.located))
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
        frontRead(windows, generation, isEnabled, windowId, eventPackage)
    }
}
