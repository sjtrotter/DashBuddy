package cloud.trotter.dashbuddy.core.database.log.dto

import kotlinx.serialization.Serializable

@Serializable
data class SnapshotWrapperDto(
    val timestamp: Long,
    val breadcrumbs: List<String> = emptyList(),
    val isGolden: Boolean = false,
    val root: UiNodeDto
)