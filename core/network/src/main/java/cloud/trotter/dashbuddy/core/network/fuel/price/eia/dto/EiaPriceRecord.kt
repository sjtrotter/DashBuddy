package cloud.trotter.dashbuddy.core.network.fuel.price.eia.dto

import kotlinx.serialization.Serializable

@Serializable
data class EiaPriceRecord(
    val period: String,
    val value: Float
)