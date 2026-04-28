package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.pipeline.accessibility.mapper.toUiNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

class ClickedPipeline @Inject constructor(
    private val source: AccessibilitySource,
    private val classifier: ClickClassifier,
    private val factory: ClickFactory,
) {
    fun output(): Flow<StateEvent> = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED }
        .filter { it.packageName == "com.doordash.driverapp" }  // drop non-DD clicks
        .mapNotNull { event ->
            val sourceNode = event.source ?: return@mapNotNull null
            val node = sourceNode.toUiNode() ?: return@mapNotNull null
            val info = classifier.classify(node)
            factory.create(node, info)
        }
}
