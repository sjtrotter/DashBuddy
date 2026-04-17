package cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa.dto

import kotlinx.serialization.Serializable

@Serializable
data class MenuItem(
    val text: String,
    val value: String
)