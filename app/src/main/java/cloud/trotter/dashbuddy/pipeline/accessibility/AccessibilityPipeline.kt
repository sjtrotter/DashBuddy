package cloud.trotter.dashbuddy.pipeline.accessibility

import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.ViewPipeline
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.WindowPipeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import javax.inject.Inject

class AccessibilityPipeline @Inject constructor(
    private val viewPipeline: ViewPipeline,
    private val windowPipeline: WindowPipeline
) {
    fun output(): Flow<StateEvent> = merge(
        viewPipeline.output(),
        windowPipeline.output()
    )
}