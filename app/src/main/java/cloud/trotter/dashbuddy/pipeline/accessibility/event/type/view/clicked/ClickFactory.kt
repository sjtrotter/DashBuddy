package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.core.data.log.ClickLogRepository
import cloud.trotter.dashbuddy.domain.model.accessibility.ClickAction
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.state.ClickEvent
import timber.log.Timber
import javax.inject.Inject

class ClickFactory @Inject constructor(
    private val clickLogRepository: ClickLogRepository
) {
    fun create(node: UiNode, action: ClickAction): ClickEvent {
        val event = ClickEvent(
            timestamp = System.currentTimeMillis(),
            action = action,
            sourceNode = node
        )
        Timber.d("Click Event Created: $action")
        clickLogRepository.log(event)
        return event
    }
}