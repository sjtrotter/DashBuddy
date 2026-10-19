package cloud.trotter.dashbuddy.feature.bubble.hud.vehicle

data class VehicleRowModel(val name: String?, val typeLabel: String?, val canSwap: Boolean, val unreadable: Boolean)

/**
 * The app adapts its data-layer Garage: the active vehicle's display name (resolved by the one owner,
 * `Vehicle.displayName()` in `:app`; null = no active vehicle) and its localized type label.
 */
data class VehicleRowInput(
    val activeName: String?,
    val typeLabel: String?,
    val anyVehicles: Boolean,
    val readable: Boolean,
)

object VehicleRowMapper {
    /** null garage = still loading → no row. */
    fun map(garage: VehicleRowInput?): VehicleRowModel? = when {
        garage == null -> null
        !garage.readable -> VehicleRowModel(null, null, canSwap = false, unreadable = true)
        else -> VehicleRowModel(
            garage.activeName,
            garage.typeLabel.takeIf { garage.activeName != null },
            canSwap = garage.anyVehicles,
            unreadable = false,
        )
    }
}
