package cloud.trotter.dashbuddy.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.pipeline.accessibility.TreeSnapshot

/**
 * Pre-classification discriminated union for all pipeline input events.
 *
 * Each variant carries raw data from its source sub-pipe. Classification
 * transforms a [PipelineEvent] into an [Observation].
 */
sealed interface PipelineEvent {
    val timestamp: Long

    /** Full-screen tree snapshot from the window sub-pipes (content-changed / state-changed). */
    data class Screen(
        override val timestamp: Long,
        val tree: UiNode,
        val snapshot: TreeSnapshot,
    ) : PipelineEvent

    /** A click on a single node, extracted from TYPE_VIEW_CLICKED. */
    data class Click(
        override val timestamp: Long,
        val node: UiNode,
    ) : PipelineEvent

    /** A notification from the notification listener service. */
    data class Notification(
        override val timestamp: Long,
        val raw: RawNotificationData,
    ) : PipelineEvent
}
