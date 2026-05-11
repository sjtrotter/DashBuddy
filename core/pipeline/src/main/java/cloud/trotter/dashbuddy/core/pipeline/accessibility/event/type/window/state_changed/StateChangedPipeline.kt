package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.state_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
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
        .filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
        .onEach {
            Timber.d("⚡ STATE_CHANGED from %s  types=0x%02x", it.className, it.contentChangeTypes)
        }
        .mapNotNull { event ->
            source.getCurrentRootNode()?.let { tree ->
                TreeSnapshot(
                    tree = tree,
                    source = TreeSnapshot.Source.STATE_CHANGED,
                    contentChangeTypes = event.contentChangeTypes,
                    packageName = event.packageName?.toString(),
                )
            }
        }
}