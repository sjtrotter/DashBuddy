package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import cloud.trotter.dashbuddy.state.event.StateEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

class ClickedPipeline @Inject constructor(
    private val source: AccessibilitySource,
    private val classifier: ClickClassifier,
    private val factory: ClickFactory
) {
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun output(): Flow<StateEvent> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED }
        .mapNotNull { event ->
            val sourceNode = event.source ?: return@mapNotNull null
            val node = UiNode.from(sourceNode) ?: return@mapNotNull null

            // Enrich
            val action = classifier.classify(node)

            // Produce
            factory.create(node, action)
        }
}