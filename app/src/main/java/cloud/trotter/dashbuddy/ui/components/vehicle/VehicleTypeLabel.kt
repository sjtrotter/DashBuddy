package cloud.trotter.dashbuddy.ui.components.vehicle

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.vehicleType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleType
import cloud.trotter.dashbuddy.R

/** Vehicle-type copy belongs to this UI module's resources. */
@StringRes
fun vehicleTypeLabel(type: VehicleType): Int = when (type) {
    VehicleType.CAR -> R.string.vehicle_type_car
    VehicleType.HYBRID -> R.string.vehicle_type_hybrid
    VehicleType.EV -> R.string.vehicle_type_ev
    VehicleType.MOTORCYCLE -> R.string.vehicle_type_motorcycle
    VehicleType.SCOOTER -> R.string.vehicle_type_scooter
    VehicleType.E_BIKE -> R.string.vehicle_type_e_bike
    VehicleType.BICYCLE -> R.string.vehicle_type_bicycle
}

/**
 * Authoritative for `:app` screens. Duplicated by choice in `:feature:bubble`'s
 * `hud/odometer/VehicleTypeLabel.kt`, its one display-name owner, because a feature module
 * cannot depend on `:app`.
 */
@Composable
fun Vehicle.displayName(): String = identityName ?: stringResource(vehicleTypeLabel(profile.vehicleType))
