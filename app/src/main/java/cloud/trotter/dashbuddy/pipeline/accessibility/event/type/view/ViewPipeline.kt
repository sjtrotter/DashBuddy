package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked.ClickedPipeline
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ViewPipeline @Inject constructor(
    private val clickedPipeline: ClickedPipeline
    // Future: private val viewLongClickedPipeline: ViewLongClickedPipeline     // maybe?
    // Future: private val viewScrolledPipeline: ViewScrolledPipeline           // maybe?
) {
    fun output(): Flow<StateEvent> = clickedPipeline.output()
    // Future: .mergeWith(viewLongClickedPipeline.output())                     // maybe?
}