package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
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
 * list and snapshots at most ONE window per burst (#1148 review G6/H6; PR #1155 review CC2/CC3): the
 * window in FRONT above a cutoff — an ENABLED application window or an ENABLED platform offer overlay
 * (a11y `TYPE_SYSTEM`, size + package, #1152 D6) — never one beneath it, and never two (that would
 * re-open the interleaving the resolver removed). The cutoff:
 * - the ACTIVE window's layer, when the active window is not our own;
 * - when OUR bubble is active (its layer would suppress everything beneath it), the window
 *   [AccessibilitySource.foregroundWindow] reads — the topmost non-own enabled application window
 *   or platform offer overlay — is emitted itself (nothing non-own sits above it by construction); if the foreground is
 *   refused, nothing is emitted.
 * Above the active window the walk is [AccessibilitySource.frontAbove] — the SAME readable-top-or-
 * refuse rule as [AccessibilitySource.foregroundWindow]: `TYPE_APPLICATION` windows plus platform offer
 * overlays (every other system-layer window — the status bar, a platform's small puck or toast, the
 * notification shade — is not, review H1 / #1152 D2), never picture-in-picture (H2), our own skipped;
 * the first decides, and an unreadable window is a barrier (nothing beneath it is emitted).
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
            // BB10: read through the source's cache — one owner of "a window's package".
            val activeIsOwn = ownPkg != null && source.packageOf(active) == ownPkg
            if (activeIsOwn) {
                // H6: our bubble's layer is no cutoff — emit the window in front of the dasher.
                // Reuse THIS enumeration (round 4): no second getWindows() per topology burst.
                when (val front = source.foregroundWindow(windows) { it in enabled }) {
                    is AccessibilitySource.Foreground.Found ->
                        snapshotOf(front.located.window, front.located.root, front.located.isOverlay)?.let { emit(it) }
                    is AccessibilitySource.Foreground.Refused -> {
                        if (front.reason == ForegroundSkipReason.SCAN_BUDGET) stats.onForegroundSkip(front.reason) // CC5
                        Timber.tag("Pipeline").v("🚫 Windows: our window active, foreground refused %s", front.reason)
                    }
                }
                return@transform
            }
            // PR #1155 review CC2/CC3: ONE winner, the same one the event path would pick — the
            // readable-top-or-refuse walk over every window ABOVE the active one
            // ([AccessibilitySource.frontAbove]). An enabled overlay over a covered DoorDash sheet
            // emits the overlay only (never both — that re-opened the interleaving); an unreadable
            // window (application, or a LARGE system window) above is a BARRIER — nothing beneath it
            // is emitted; a foreign application window on top emits nothing.
            when (val front = source.frontAbove(windows, active) { it in enabled }) {
                is AccessibilitySource.Foreground.Found ->
                    snapshotOf(front.located.window, front.located.root, front.located.isOverlay)?.let { emit(it) }
                is AccessibilitySource.Foreground.Refused -> {
                    // CC5: a budget refusal is a gate decision worth sizing in the field pull.
                    if (front.reason == ForegroundSkipReason.SCAN_BUDGET) stats.onForegroundSkip(front.reason)
                    Timber.tag("Pipeline").v("🚫 Windows: nothing emitted above the active window (%s)", front.reason)
                }
            }
        }
}
