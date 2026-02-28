package cloud.trotter.dashbuddy.data.vehicle.api.dto

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