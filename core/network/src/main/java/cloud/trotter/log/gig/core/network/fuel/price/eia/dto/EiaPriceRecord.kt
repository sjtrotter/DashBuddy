package cloud.trotter.log.gig.core.network.fuel.price.eia.dto

import kotlinx.serialization.Serializable

@Serializable
data class EiaPriceRecord(
    val period: String,
    val value: Float
)