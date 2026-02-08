package cloud.trotter.dashbuddy.pipeline.features.click

import cloud.trotter.dashbuddy.data.log.clicks.ClickLogRepository
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.click.ClickAction
import cloud.trotter.dashbuddy.state.event.ClickEvent
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