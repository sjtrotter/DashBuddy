package cloud.trotter.dashbuddy.core.network.fuel.price.eia.dto

import kotlinx.serialization.Serializable

@Serializable
data class EiaDataNode(
    val data: List<EiaPriceRecord>
)