package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
 * list and snapshots TRUE OVERLAYS only (#1148 review G6/H6): ENABLED-package application windows
 * above a cutoff, never one beneath it — that would re-open the interleaving the resolver removed
 * (the activity under an active DoorDash sheet). The cutoff:
 * - the ACTIVE window's layer, when the active window is not our own;
 * - when OUR bubble is active (its layer would suppress everything beneath it), the window
 *   [AccessibilitySource.foregroundWindow] reads — the topmost non-own enabled application window
 *   — is emitted itself (nothing non-own sits above it by construction); if the foreground is
 *   refused, nothing is emitted.
 * Candidates match [AccessibilitySource.foregroundWindow]: `TYPE_APPLICATION` windows only (review
 * H1; overlays that surface as accessibility `TYPE_SYSTEM` are #1152), never picture-in-picture
 * (H2). No active window → nothing (accepted: the event-driven pipelines still cover that case).
 */
class WindowsChangedPipeline @Inject constructor(
    private val source: AccessibilitySource,
    private val platformPreferences: PlatformPreferences,
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
            Timber.tag("Pipeline").d("\uD83E\uDE9F Window list: %d windows", windows.size)
            windows.forEachIndexed { i, w ->
                // The window TITLE is app-controlled text (#1148 review G1) — logged by LENGTH only,
                // even at DEBUG: app.log leaves the device in every post-dash pull.
                Timber.tag("Pipeline").d(
                    "  [%d] id=%d type=%d layer=%d titleLen=%d active=%s focused=%s",
                    i, w.id, w.type, w.layer, w.title?.length ?: 0, w.isActive, w.isFocused
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
            fun snapshotOf(w: AccessibilityWindowInfo, root: AccessibilityNodeInfo): TreeSnapshot? =
                // #1148 review F6: the shared snapshot builder (one WindowContext, one map path).
                source.getWindowSnapshot(w, root, totalCount)?.let {
                    TreeSnapshot(it.tree, it.packageName, it.windowContext, trigger())
                }

            val ownPkg = source.ownPackage()
            val activeIsOwn = ownPkg != null && active.root?.packageName?.toString() == ownPkg
            if (activeIsOwn) {
                // H6: our bubble's layer is no cutoff — emit the window in front of the dasher.
                // Reuse THIS enumeration (round 4): no second getWindows() per topology burst.
                when (val front = source.foregroundWindow(windows) { it in enabled }) {
                    is AccessibilitySource.Foreground.Found ->
                        snapshotOf(front.located.window, front.located.root)?.let { emit(it) }
                    is AccessibilitySource.Foreground.Refused ->
                        Timber.tag("Pipeline").v("🚫 Windows: our window active, foreground refused %s", front.reason)
                }
                return@transform
            }
            for (w in windows) {
                if (w.isActive || w.layer <= active.layer) continue // never beneath the active window
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue // H1, #1152
                if (w.isInPictureInPictureMode) continue // H2: a PiP (e.g. Maps) is never a platform frame
                // Pre-map package read on the native root (#435 item 3): only ENABLED platforms —
                // never our own bubble or other apps (#4).
                val nativeRoot = w.root ?: continue
                if (nativeRoot.packageName?.toString() !in enabled) continue
                snapshotOf(w, nativeRoot)?.let { emit(it) }
            }
        }
}
