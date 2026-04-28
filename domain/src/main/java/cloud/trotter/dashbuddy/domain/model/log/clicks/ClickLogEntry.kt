package cloud.trotter.dashbuddy.domain.model.log.clicks

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

data class ClickLogEntry(
    val timestamp: Long,
    val dateReadable: String, // "12:30:05"
    val action: ClickInfo,
    val targetId: String?,
    val targetText: String?,
    // We store the specific button node for deep debugging
    val sourceNode: UiNode?
)