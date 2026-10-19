package cloud.trotter.dashbuddy.ui.bubble

sealed interface VehicleChange {
    data object SWITCHED : VehicleChange
    data class RETIRED(val id: String, val wasActive: Boolean) : VehicleChange
}
