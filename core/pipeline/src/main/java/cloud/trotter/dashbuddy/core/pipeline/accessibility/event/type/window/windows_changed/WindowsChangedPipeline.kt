package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.coalesce.coalesceByKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import timber.log.Timber
import javax.inject.Inject

/**
 * Sub-pipeline that reacts to TYPE_WINDOWS_CHANGED events — fired when the
 * accessibility window list changes (a window appears, disappears, or changes focus).
 *
 * Unlike ContentChanged/StateChanged, which snapshot ONE window per frame (the active enabled
 * window, else the readable enabled window in front — #1148), this pipeline enumerates the window
 * list and snapshots TRUE OVERLAYS only (#1148 review G6/H6): ENABLED-package application windows
 * and ENABLED platform offer overlays (a11y `TYPE_SYSTEM`, size + package —
 * [AccessibilitySource.isOverlayCandidate], #1152 D6) above a cutoff, never one beneath it — that would re-open the interleaving the resolver removed
 * (the activity under an active DoorDash sheet). The cutoff:
 * - the ACTIVE window's layer, when the active window is not our own;
 * - when OUR bubble is active (its layer would suppress everything beneath it), the window
 *   [AccessibilitySource.foregroundWindow] reads — the topmost non-own enabled application window
 *   or platform offer overlay — is emitted itself (nothing non-own sits above it by construction); if the foreground is
 *   refused, nothing is emitted.
 * Candidates match [AccessibilitySource.foregroundWindow]: `TYPE_APPLICATION` windows plus platform
 * offer overlays (every other system-layer window — the status bar, a platform's small puck or
 * toast, the notification shade — is not, review H1 / #1152 D2), never picture-in-picture (H2).
 * No active window → nothing (accepted: the event-driven pipelines still cover that case).
 * Every overlay emitted is counted (`PipelineStats.onOverlaySnapshot`).
 */
class WindowsChangedPipeline @Inject constructor(
    private val source: AccessibilitySource,
    private val platformPreferences: PlatformPreferences,
    private val stats: PipelineStats,
) {
    fun output(): Flow<TreeSnapshot> = source.events
        .filter { it.type == AccessibilityEvent.TYPE_WINDOWS_CHANGED }
        // #1148 D3: the same bounded shape as content changes — quiet 100 ms, scheduled max-wait
        // 300 ms — on a single key (topology is one global stream). The accumulator is the count.
        .coalesceByKey(
            quietMs = 100L,
            maxWaitMs = 300L,
            keyOf = { 0 },
            merge = { acc: Int?, _ -> (acc ?: 0) + 1 },
        )
        .onEach { n -> Timber.tag("Pipeline").d("\uD83E\uDE9F WINDOWS_CHANGED (coalesced n=%d)", n) }
        .transform { coalesced ->
            val windows = source.getWindows()
            // #1152 D2: measured once per burst, shared by the list log and the overlay checks.
            val displayArea = source.displayArea()
            Timber.tag("Pipeline").d("\uD83E\uDE9F Window list: %d windows", windows.size)
            windows.forEachIndexed { i, w ->
                // The window TITLE is app-controlled text (#1148 review G1) — logged by LENGTH only,
                // even at DEBUG: app.log leaves the device in every post-dash pull. area% (#1152) is
                // the window's share of the display, an int — what the overlay size rule reads.
                val areaPct = if (displayArea > 0L) (source.areaOf(w) * 100 / displayArea).toInt() else -1
                Timber.tag("Pipeline").d(
                    "  [%d] id=%d type=%d layer=%d area%%=%d titleLen=%d active=%s focused=%s",
                    i, w.id, w.type, w.layer, areaPct, w.title?.length ?: 0, w.isActive, w.isFocused
                )
            }

            val totalCount = windows.size

            val active = windows.firstOrNull { it.isActive }
            if (active == null) {
                Timber.tag("Pipeline").v("🚫 Windows: no active window — nothing emitted")
                return@transform
            }
            val enabled = platformPreferences.enabledPackages.value
            fun trigger() = TreeSnapshot.Trigger(
                reason = TreeSnapshot.Trigger.Reason.WINDOWS,
                changeTypes = 0,
                coalescedEvents = coalesced,
                spanMs = 0L,
            )
            fun snapshotOf(w: AccessibilityWindowInfo, root: AccessibilityNodeInfo, overlay: Boolean = false): TreeSnapshot? =
                // #1148 review F6: the shared snapshot builder (one WindowContext, one map path).
                source.getWindowSnapshot(w, root, totalCount)?.let {
                    if (overlay) stats.onOverlaySnapshot() // #1152
                    TreeSnapshot(it.tree, it.packageName, it.windowContext, trigger())
                }

            val ownPkg = source.ownPackage()
            val activeIsOwn = ownPkg != null && active.root?.packageName?.toString() == ownPkg
            if (activeIsOwn) {
                // H6: our bubble's layer is no cutoff — emit the window in front of the dasher.
                // Reuse THIS enumeration (round 4): no second getWindows() per topology burst.
                when (val front = source.foregroundWindow(windows) { it in enabled }) {
                    is AccessibilitySource.Foreground.Found ->
                        snapshotOf(front.located.window, front.located.root, front.located.isOverlay)?.let { emit(it) }
                    is AccessibilitySource.Foreground.Refused ->
                        Timber.tag("Pipeline").v("🚫 Windows: our window active, foreground refused %s", front.reason)
                }
                return@transform
            }
            for (w in windows) {
                if (w.isActive || w.layer <= active.layer) continue // never beneath the active window
                if (w.isInPictureInPictureMode) continue // H2: a PiP (e.g. Maps) is never a platform frame
                val isOverlay: Boolean
                val nativeRoot: AccessibilityNodeInfo = when (w.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> {
                        isOverlay = false
                        w.root ?: continue
                    }
                    AccessibilityWindowInfo.TYPE_SYSTEM -> {
                        // #1152 D6: only a platform offer overlay (size, then package) — never the
                        // status bar, a puck or toast (no root fetch), nor the notification shade.
                        // An unreadable large window (BB1) has nothing to emit — skipped here; the
                        // foreground read refuses on it.
                        val probe = source.overlayProbe(w, displayArea) as? AccessibilitySource.OverlayProbe.Candidate ?: continue
                        if (probe.packageName !in enabled) continue // a disabled overlay platform is never mapped
                        isOverlay = true
                        probe.root ?: w.root ?: continue
                    }
                    else -> continue // H1: IME, accessibility overlays, split-screen divider
                }
                // Pre-map package read on the native root (#435 item 3): only ENABLED platforms —
                // never our own bubble or other apps (#4).
                val livePkg = nativeRoot.packageName?.toString()
                if (livePkg !in enabled) continue
                if (isOverlay && livePkg !in Platform.overlayPackages) continue // re-verified on the mapped root
                snapshotOf(w, nativeRoot, isOverlay)?.let { emit(it) }
            }
        }
}
