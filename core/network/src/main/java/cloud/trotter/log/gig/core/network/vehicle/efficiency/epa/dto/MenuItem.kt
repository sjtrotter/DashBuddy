package cloud.trotter.log.gig.core.network.vehicle.efficiency.epa.dto

import kotlinx.serialization.Serializable

@Serializable
data class MenuItem(
    val text: String,
    val value: String
)