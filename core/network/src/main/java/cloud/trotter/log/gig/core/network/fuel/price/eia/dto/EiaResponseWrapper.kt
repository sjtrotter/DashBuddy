package cloud.trotter.log.gig.core.network.fuel.price.eia.dto

import kotlinx.serialization.Serializable

@Serializable
data class EiaResponseWrapper(
    val response: EiaDataNode
)