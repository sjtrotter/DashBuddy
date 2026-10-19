package cloud.trotter.dashbuddy.feature.bubble.hud.vehicle

import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle

data class VehicleRowModel(val vehicle: Vehicle?, val canSwap: Boolean, val unreadable: Boolean)

/** The app adapts its data-layer Garage; the feature owns the vehicle's display copy. */
data class VehicleRowInput(
    val active: Vehicle?,
    val switchable: Boolean,
    val readable: Boolean,
)

object VehicleRowMapper {
    /** null garage = still loading → no row. */
    fun map(garage: VehicleRowInput?): VehicleRowModel? = when {
        garage == null -> null
        !garage.readable -> VehicleRowModel(null, canSwap = false, unreadable = true)
        else -> VehicleRowModel(
            garage.active,
            canSwap = garage.switchable,
            unreadable = false,
        )
    }
}
