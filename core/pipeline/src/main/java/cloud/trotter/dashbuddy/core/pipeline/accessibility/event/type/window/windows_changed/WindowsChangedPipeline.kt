package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject

/**
 * Sub-pipeline that reacts to TYPE_WINDOWS_CHANGED events — fired when the
 * accessibility window list changes (a window appears, disappears, or changes focus).
 *
 * Unlike ContentChanged/StateChanged which snapshot [rootInActiveWindow], this pipeline
 * enumerates ALL windows via [AccessibilityService.getWindows()] and snapshots each
 * non-active application window from a watched package. This captures overlay windows
 * (e.g., Uber offer screens) that are invisible to the single-window pipelines.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class WindowsChangedPipeline @Inject constructor(
    private val source: AccessibilitySource
) {
    fun output(): Flow<TreeSnapshot> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED }
        .debounce(100L)
        .onEach { Timber.d("\uD83E\uDE9F WINDOWS_CHANGED") }
        .flatMapConcat { _ ->
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
            windows.asFlow()
                .filter { w ->
                    w.type == AccessibilityWindowInfo.TYPE_APPLICATION && !w.isActive
                }
                .mapNotNull { w ->
                    // Get the package from the native root before converting to UiNode
                    val nativeRoot = w.root ?: return@mapNotNull null
                    val pkg = nativeRoot.packageName?.toString()
                    val tree = try {
                        source.getRootForWindow(w)
                    } catch (_: Exception) {
                        null
                    } ?: return@mapNotNull null

                    TreeSnapshot(
                        tree = tree,
                        source = TreeSnapshot.Source.WINDOWS_CHANGED,
                        packageName = pkg,
                        windowContext = TreeSnapshot.WindowContext(
                            windowId = w.id,
                            windowType = w.type,
                            windowTitle = w.title?.toString(),
                            windowLayer = w.layer,
                            isActive = w.isActive,
                            isFocused = w.isFocused,
                            totalWindowCount = totalCount,
                        ),
                    )
                }
        }
}
