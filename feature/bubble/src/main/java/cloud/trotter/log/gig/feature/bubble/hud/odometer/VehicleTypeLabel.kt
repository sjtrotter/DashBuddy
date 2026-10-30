package cloud.trotter.log.gig.feature.bubble.hud.odometer

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.model.vehicle.vehicleType
import cloud.trotter.log.gig.domain.model.vehicle.VehicleType
import cloud.trotter.log.gig.feature.bubble.R

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

fun Vehicle.displayName(context: Context): String = identityName ?: context.getString(vehicleTypeLabel(profile.vehicleType))

fun vehicleTypeIcon(type: VehicleType): ImageVector = when (type) {
    VehicleType.CAR, VehicleType.HYBRID -> Icons.Outlined.DirectionsCar
    VehicleType.EV -> Icons.Outlined.ElectricCar
    VehicleType.MOTORCYCLE -> Icons.Outlined.TwoWheeler
    VehicleType.SCOOTER -> Icons.Outlined.Moped
    VehicleType.E_BIKE -> Icons.Outlined.ElectricBike
    VehicleType.BICYCLE -> Icons.Outlined.PedalBike
}
