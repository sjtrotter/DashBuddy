package cloud.trotter.dashbuddy.data.vehicle.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class MenuItem(
    val text: String,
    val value: String
)