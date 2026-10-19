package cloud.trotter.dashbuddy.ui.components.vehicle

import androidx.annotation.StringRes
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
