package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.content_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject

@OptIn(FlowPreview::class)
class ContentChangedPipeline @Inject constructor(
    private val source: AccessibilitySource
) {
    fun output(): Flow<UiNode> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }
        .onEach { Timber.v("🌊 FLOOD: Content Change from ${it.className}") }
        .debounceWithTimeout(150L, 300L)
        .onEach { Timber.d("💧 DRIP: Survivor! ${it.className}") }
        .mapNotNull { _ ->
            source.getCurrentRootNode()
        }
}