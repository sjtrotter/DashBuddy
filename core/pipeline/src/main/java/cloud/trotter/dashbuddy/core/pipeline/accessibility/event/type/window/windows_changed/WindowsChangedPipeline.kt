package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
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
 * Unlike ContentChanged/StateChanged, which snapshot ONE window per frame (the active watched
 * window, else the topmost watched application window — #1148), this pipeline
 * enumerates ALL windows via [AccessibilityService.getWindows()] and snapshots each
 * non-active application window from a watched package. This captures overlay windows
 * (e.g., Uber offer screens) that are invisible to the single-window pipelines.
 */
class WindowsChangedPipeline @Inject constructor(
    private val source: AccessibilitySource
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
        .onEach { n -> Timber.d("\uD83E\uDE9F WINDOWS_CHANGED (coalesced n=%d)", n) }
        .transform { coalesced ->
            val windows = source.getWindows()
            Timber.d(
                "\uD83E\uDE9F Window list: %d windows",
                windows.size
            )
            windows.forEachIndexed { i, w ->
                Timber.d(
                    "  [%d] id=%d type=%d layer=%d title=%s active=%s focused=%s",
                    i, w.id, w.type, w.layer, w.title, w.isActive, w.isFocused
                )
            }

            val totalCount = windows.size

            // Emit a TreeSnapshot for each non-active application window.
            // The active window is already captured by StateChanged/ContentChanged.
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || w.isActive) continue
                // Pre-map package read on the native root (#435 item 3). Only snapshot
                // watched-platform windows (e.g. an Uber overlay) — never our own bubble overlay or
                // other apps. Prevents recognizing our own UI (#4).
                val nativeRoot = w.root ?: continue
                if (nativeRoot.packageName?.toString() !in Platform.watchedPackages) continue
                // #1148 review F6: the shared snapshot builder (one WindowContext, one map path).
                val snapshot = source.getWindowSnapshot(w, nativeRoot, totalCount) ?: continue
                emit(
                    TreeSnapshot(
                        tree = snapshot.tree,
                        packageName = snapshot.packageName,
                        windowContext = snapshot.windowContext,
                        trigger = TreeSnapshot.Trigger(
                            reason = TreeSnapshot.Trigger.Reason.WINDOWS,
                            changeTypes = 0,
                            coalescedEvents = coalesced,
                            spanMs = 0L,
                        ),
                    )
                )
            }
        }
}
