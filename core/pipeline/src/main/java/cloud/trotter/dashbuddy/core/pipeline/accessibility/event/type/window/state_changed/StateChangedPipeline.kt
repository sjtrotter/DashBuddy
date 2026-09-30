package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.state_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.EventSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.snapshotForEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject

class StateChangedPipeline @Inject constructor(
    private val source: AccessibilitySource,
    private val platformPreferences: PlatformPreferences,
    private val stats: PipelineStats,
) {
    fun output(): Flow<TreeSnapshot> = source.events
        .filter { it.type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
        .onEach {
            Timber.tag("Pipeline").d(
                "⚡ STATE_CHANGED window=%d from %s  types=0x%02x",
                it.windowId, it.className, it.contentChangeTypes,
            )
        }
        .mapNotNull { event ->
            // Immediate (no coalescing). #1148 D4 (review F1/G5): active enabled window, else the
            // readable enabled window in front, else refuse; package-gated before and after the map.
            val resolved = source.snapshotForEvent(event.windowId, event.packageName) {
                it in platformPreferences.enabledPackages.value
            }
            val snapshot = when (resolved) {
                is EventSnapshot.Resolved -> {
                    if (resolved.viaOverlay) stats.onOverlaySnapshot() // #1152
                    resolved.snapshot
                }
                is EventSnapshot.Skipped -> {
                    stats.onForegroundSkip(resolved.reason) // #1148 review H3
                    return@mapNotNull null
                }
            }
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
