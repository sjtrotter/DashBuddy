package cloud.trotter.dashbuddy.state.event

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.click.ClickAction

data class ClickEvent(
    override val timestamp: Long,
    val action: ClickAction,
    val sourceNode: UiNode
) : StateEvent