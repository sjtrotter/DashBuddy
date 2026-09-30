package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.mapWindow
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
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
 * list and snapshots at most ONE window per burst (#1148 review G6/H6; PR #1155 reviews CC2/CC3/DD3/
 * HH1) — chosen by the SAME rules the event path applies, over the SAME three-valued
 * [AccessibilitySource.resolveActive], so the two paths never disagree on one list:
 * - **Enabled** active window → the event path owns it (and never reads a non-active application
 *   window above it) → only an ENABLED platform offer overlay in front above it is emitted
 *   ([FrontWindowWalk.overlayFront]);
 * - **Unknown(window)** (flagged, root unreadable) → the same overlay scan off its layer; no overlay
 *   → nothing, `topologySkip{FRONT_UNREADABLE}` (the event path owns that frame);
 * - **NotEnabled** → our bubble: [AccessibilitySource.foregroundWindow]'s window in front; another
 *   non-enabled window: [FrontWindowWalk.frontAbove]'s single winner;
 * - **Unknown(none)** (zero or ≥ 2 flagged) → nothing, `topologySkip{NO_ACTIVE_ROOT}`.
 * Every walk is readable-top-or-refuse: an unreadable window above is a BARRIER (nothing beneath it is
 * emitted) and a foreign application window on top emits nothing. Refusals are counted in
 * `topologySkip{…}` (review FF4).
 * Every overlay emitted is counted (`PipelineStats.onOverlaySnapshot`, in the one window builder).
 *
 * [FrontWindowWalk]: `cloud.trotter.dashbuddy.core.pipeline.accessibility.input.FrontWindowWalk`.
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
            val gen = source.generation // KK1: BEFORE the enumeration, passed to every walk / fetch
            val windows = source.getWindows()
            // #1152 D2: measured once per burst, shared by the list log and the overlay checks.
            // DD11: ONE display read per burst, passed down to every walk of this burst.
            val display = source.walk.lazyDisplayArea()
            val displayArea = display.value
            Timber.tag("Pipeline").d("\uD83E\uDE9F Window list: %d windows", windows.size)
            windows.forEachIndexed { i, w ->
                // The window TITLE is app-controlled text (#1148 review G1) — logged by LENGTH only,
                // even at DEBUG: app.log leaves the device in every post-dash pull. area% (#1152) is
                // the window's share of the display, an int — what the overlay size rule reads.
                val areaPct = if (displayArea > 0L) (source.walk.areaOf(w) * 100 / displayArea).toInt() else -1
                Timber.tag("Pipeline").d(
                    "  [%d] id=%d type=%d layer=%d area%%=%d titleLen=%d active=%s focused=%s",
                    i, w.id, w.type, w.layer, areaPct, w.title?.length ?: 0, w.isActive, w.isFocused
                )
            }


            val enabled = platformPreferences.enabledPackages.value
            fun trigger() = TreeSnapshot.Trigger(
                reason = TreeSnapshot.Trigger.Reason.WINDOWS,
                changeTypes = 0,
                coalescedEvents = coalesced,
                spanMs = 0L,
            )
            fun snapshotOf(located: AccessibilitySource.LocatedWindow): TreeSnapshot? =
                // #1148 review F6: the shared snapshot builder (one WindowContext, one map path; it
                // also counts overlay frames from the walk's own fact — FF6/KK4).
                source.mapWindow(located)?.let {
                    TreeSnapshot(it.tree, it.packageName, it.windowContext, trigger())
                }

            val isEnabled: (String?) -> Boolean = { it in enabled }
            val ownPkg = source.ownPackage()
            // PR #1155 review HH1/KK2: the SAME rule the event path applies over THIS enumeration — the
            // single flagged window; the overlay scan FIRST, off its layer (no root fetched while an
            // enabled overlay is up); only on no overlay is the active window classified (a fresh root,
            // never a memoized package; a null package is never "not enabled").
            val active = source.activeFromEnumeration(windows).single
            if (active == null) {
                stats.onTopologySkip(ForegroundSkipReason.NO_ACTIVE_ROOT) // FF4
                Timber.tag("Pipeline").v("🚫 Windows: no (single) active window — nothing emitted")
                return@transform
            }
            when (val scan = source.walk.overlayFront(windows, active, isEnabled, display, gen)) {
                is AccessibilitySource.OverlayScan.Overlay -> snapshotOf(scan.located)?.let { emit(it) }
                is AccessibilitySource.OverlayScan.Refused -> {
                    stats.onTopologySkip(scan.reason) // FF4
                    Timber.tag("Pipeline").v("🚫 Windows: overlay scan refused %s", scan.reason)
                }
                AccessibilitySource.OverlayScan.None -> when (val resolution = source.resolveActive(active, isEnabled, gen)) {
                    // Flagged but unreadable → nothing (the event path owns that frame).
                    is AccessibilitySource.ActiveWindow.Unknown -> {
                        stats.onTopologySkip(ForegroundSkipReason.FRONT_UNREADABLE)
                        Timber.tag("Pipeline").v("🚫 Windows: active window unreadable, no overlay in front — nothing emitted")
                    }
                    // CC2/CC3/DD3: the event path owns the enabled active window (and never reads a
                    // non-active application window above it) — only an overlay is ever emitted here.
                    is AccessibilitySource.ActiveWindow.Enabled ->
                        Timber.tag("Pipeline").v("🚫 Windows: enabled active window, no overlay in front — nothing emitted")
                    is AccessibilitySource.ActiveWindow.NotEnabled -> {
                        val isOwn = ownPkg != null && resolution.root?.packageName?.toString() == ownPkg
                        // H6: our bubble's layer is no cutoff — the window in front of the dasher; another
                        // non-enabled window → `frontAbove`'s single winner. Either way an unreadable
                        // window above is a BARRIER and a foreign app on top emits nothing.
                        val front = if (isOwn) {
                            source.foregroundWindow(windows, isEnabled, display, gen)
                        } else {
                            source.walk.frontAbove(windows, active, isEnabled, display, gen)
                        }
                        when (front) {
                            is AccessibilitySource.Foreground.Found -> snapshotOf(front.located)?.let { emit(it) }
                            is AccessibilitySource.Foreground.Refused -> {
                                stats.onTopologySkip(front.reason) // FF4: the topology path's own census
                                Timber.tag("Pipeline").v("🚫 Windows: nothing emitted in front (%s)", front.reason)
                            }
                        }
                    }
                }
            }
        }
}
