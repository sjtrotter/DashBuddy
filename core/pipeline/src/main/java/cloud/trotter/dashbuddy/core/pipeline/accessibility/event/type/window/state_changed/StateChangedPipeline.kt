package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.state_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.snapshotForEventWindow
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject

class StateChangedPipeline @Inject constructor(
    private val source: AccessibilitySource
) {
    fun output(): Flow<TreeSnapshot> = source.events
        .filter { it.type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
        .onEach {
            Timber.d(
                "⚡ STATE_CHANGED window=%d from %s  types=0x%02x",
                it.windowId, it.className, it.contentChangeTypes,
            )
        }
        .mapNotNull { event ->
            // Immediate (no coalescing). #1148 D4: snapshot the EVENT's window, active-root
            // fallback; package-gated before and after the map (#435 item 3, #4).
            val snapshot = source.snapshotForEventWindow(event.windowId, event.packageName)
                ?: return@mapNotNull null
            TreeSnapshot(
                tree = snapshot.tree,
                packageName = snapshot.packageName,
                windowContext = snapshot.windowContext,
                trigger = TreeSnapshot.Trigger(
                    reason = TreeSnapshot.Trigger.Reason.STATE,
                    changeTypes = event.contentChangeTypes,
                    coalescedEvents = 1,
                    spanMs = 0L,
                ),
            )
        }
}
