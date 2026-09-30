package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
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
 * list and snapshots TRUE OVERLAYS only (#1148 review G6): ENABLED-package windows whose layer is
 * ABOVE the active window's — e.g. an Uber offer (`TYPE_APPLICATION_OVERLAY`, accessibility
 * `TYPE_SYSTEM`) over DoorDash. A window BENEATH the active one (the activity under a DoorDash
 * sheet) is never emitted — that would re-open the interleaving the resolver removed. Candidate
 * types match [AccessibilitySource.foregroundWindow]: application windows and readable
 * known-platform system windows. No active window → nothing.
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

            val active = windows.firstOrNull { it.isActive }
            if (active == null) {
                Timber.v("🚫 Windows: no active window — nothing emitted")
                return@transform
            }
            val enabled = platformPreferences.enabledPackages.value
            for (w in windows) {
                if (w.isActive || w.layer <= active.layer) continue // never beneath the active window
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    w.type != AccessibilityWindowInfo.TYPE_SYSTEM
                ) continue
                // Pre-map package read on the native root (#435 item 3): only ENABLED platforms —
                // never our own bubble or other apps (#4); a system window must also be a known
                // platform (the status bar is never read).
                val nativeRoot = w.root ?: continue
                val pkg = nativeRoot.packageName?.toString()
                if (pkg !in enabled || pkg !in Platform.watchedPackages) continue
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
