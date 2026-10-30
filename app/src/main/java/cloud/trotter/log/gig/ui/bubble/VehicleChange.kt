package cloud.trotter.log.gig.ui.bubble

sealed interface VehicleChange {
    data object SWITCHED : VehicleChange
}
