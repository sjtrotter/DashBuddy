package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.vehicleType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleType
import cloud.trotter.dashbuddy.feature.bubble.R

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
 * The one display-name owner in `:feature:bubble`. Duplicated by choice from
 * `:app`'s `ui/components/vehicle/VehicleTypeLabel.kt`, authoritative for `:app` screens;
 * a feature module cannot depend on `:app`.
 */
@Composable
fun Vehicle.displayName(): String = identityName ?: stringResource(vehicleTypeLabel(profile.vehicleType))
