package cloud.trotter.dashbuddy.domain.model.state

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickAction
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

data class ClickEvent(
    override val timestamp: Long,
    val action: ClickAction,
    val sourceNode: UiNode
) : StateEvent