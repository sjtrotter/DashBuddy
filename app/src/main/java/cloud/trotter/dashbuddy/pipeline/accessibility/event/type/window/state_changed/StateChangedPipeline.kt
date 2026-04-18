package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.state_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.pipeline.accessibility.mapper.toUiNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

class StateChangedPipeline @Inject constructor(
    private val source: AccessibilitySource
) {
    fun output(): Flow<UiNode> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
        .mapNotNull { event ->
            event.source.toUiNode()
        }
}