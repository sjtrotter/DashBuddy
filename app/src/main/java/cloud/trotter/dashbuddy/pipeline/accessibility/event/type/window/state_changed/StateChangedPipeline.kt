package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.state_changed

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

class StateChangedPipeline @Inject constructor(
    private val source: AccessibilitySource
) {
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun output(): Flow<UiNode> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
        .mapNotNull { event ->
            UiNode.from(event.source)
        }
}