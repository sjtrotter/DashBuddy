package cloud.trotter.dashbuddy.domain.model.state

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

data class ClickEvent(
    override val timestamp: Long,
    val info: ClickInfo,
    val sourceNode: UiNode,
) : StateEvent
