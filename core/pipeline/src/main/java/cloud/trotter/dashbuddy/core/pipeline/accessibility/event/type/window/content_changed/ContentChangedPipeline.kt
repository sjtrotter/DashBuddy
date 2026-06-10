package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.content_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.BuildConfig
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@OptIn(FlowPreview::class)
class ContentChangedPipeline @Inject constructor(
    private val source: AccessibilitySource
) {
    /** OR-accumulates contentChangeTypes across the debounce window. */
    private val pendingChangeTypes = AtomicInteger(0)

    fun output(): Flow<TreeSnapshot> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }
        .onEach {
            val types = it.contentChangeTypes
            pendingChangeTypes.getAndUpdate { prev -> prev or types }
            Timber.v("🌊 FLOOD: Content Change from %s  types=0x%02x", it.className, types)
        }
        .debounceWithTimeout(150L, 300L)
        .onEach {
            Timber.d("💧 DRIP: triggered by %s, accumulated types=0x%02x", it.className, pendingChangeTypes.get())
        }
        .mapNotNull { event ->
            val types = pendingChangeTypes.getAndSet(0)
            val snapshot = source.getCurrentRootSnapshot() ?: return@mapNotNull null
            // Attribute to the window actually on screen, not the triggering event. Drop snapshots
            // of non-target windows (our own bubble overlay, launcher, etc.) so we never recognize
            // our own UI as the platform — the #4 self-recognition feedback loop.
            if (snapshot.packageName !in Platform.watchedPackages()) {
                Timber.v(
                    "🚫 Skip active window: non-target pkg=%s (event pkg=%s)",
                    snapshot.packageName, event.packageName,
                )
                return@mapNotNull null
            }
            if (BuildConfig.DEBUG) {
                Timber.d("🌳 Tree snapshot: %d nodes, pkg=%s", countNodes(snapshot.tree), snapshot.packageName)
            }
            TreeSnapshot(
                tree = snapshot.tree,
                source = TreeSnapshot.Source.CONTENT_CHANGED,
                contentChangeTypes = types,
                packageName = snapshot.packageName,
            )
        }

    private fun countNodes(node: UiNode): Int =
        1 + node.children.sumOf { countNodes(it) }
}