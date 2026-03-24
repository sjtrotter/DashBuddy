package cloud.trotter.dashbuddy.core.database.log.dto

import kotlinx.serialization.Serializable

@Serializable
data class BoundingBoxDto(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)