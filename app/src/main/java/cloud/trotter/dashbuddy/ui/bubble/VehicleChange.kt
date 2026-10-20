package cloud.trotter.dashbuddy.ui.bubble

sealed interface VehicleChange {
    data object SWITCHED : VehicleChange
}
