package cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa.dto

import kotlinx.serialization.Serializable

/**
 * fueleconomy.gov wraps all their menu responses (years, makes, models)
 * in this exact same structure.
 */
@Serializable
data class MenuItemsResponse(
    @Serializable(with = MenuItemListSerializer::class)
    val menuItem: List<MenuItem> = emptyList()
)