package cloud.trotter.dashbuddy.data.log.snapshots

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import kotlinx.serialization.Serializable

@Serializable
data class SnapshotWrapper(
    val timestamp: Long,
    val breadcrumbs: List<String> = emptyList(), // Default for backward compat
    val isGolden: Boolean = false, // If true, never auto-prune this file
    val root: UiNode
)