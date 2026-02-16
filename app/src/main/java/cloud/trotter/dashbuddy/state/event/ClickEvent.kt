package cloud.trotter.dashbuddy.state.event

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked.ClickAction
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode

data class ClickEvent(
    override val timestamp: Long,
    val action: ClickAction,
    val sourceNode: UiNode
) : StateEvent