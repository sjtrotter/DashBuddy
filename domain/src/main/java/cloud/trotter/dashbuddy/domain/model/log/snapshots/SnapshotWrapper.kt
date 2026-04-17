package cloud.trotter.dashbuddy.domain.model.log.snapshots

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

data class SnapshotWrapper(
    val timestamp: Long,
    val breadcrumbs: List<String> = emptyList(),
    val isGolden: Boolean = false,
    val root: UiNode
)