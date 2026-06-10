package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.state_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.state.Platform
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
            val snapshot = source.getCurrentRootSnapshot() ?: return@mapNotNull null
            // Attribute to the on-screen window, not the event; drop non-target windows (e.g. our
            // own bubble overlay) so we don't recognize our own UI as the platform (#4).
            if (snapshot.packageName !in Platform.watchedPackages()) {
                Timber.v(
                    "🚫 Skip active window: non-target pkg=%s (event pkg=%s)",
                    snapshot.packageName, event.packageName,
                )
                return@mapNotNull null
            }
            TreeSnapshot(
                tree = snapshot.tree,
                source = TreeSnapshot.Source.STATE_CHANGED,
                contentChangeTypes = event.contentChangeTypes,
                packageName = snapshot.packageName,
            )
        }
}