package cloud.trotter.dashbuddy.core.database.log.dto

import kotlinx.serialization.Serializable

@Serializable
data class ClickLogEntryDto(
    val timestamp: Long,
    val dateReadable: String,
    val action: String,
    val targetId: String?,
    val targetText: String?,
    val sourceNode: UiNodeDto?
)