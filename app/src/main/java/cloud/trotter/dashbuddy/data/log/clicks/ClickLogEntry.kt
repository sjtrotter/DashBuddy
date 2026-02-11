package cloud.trotter.dashbuddy.data.log.clicks

import cloud.trotter.dashbuddy.pipeline.accessibility.click.ClickAction
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import kotlinx.serialization.Serializable

@Serializable
data class ClickLogEntry(
    val timestamp: Long,
    val dateReadable: String, // "12:30:05"
    val action: ClickAction,
    val targetId: String?,
    val targetText: String?,
    // We store the specific button node for deep debugging
    val sourceNode: UiNode?
)