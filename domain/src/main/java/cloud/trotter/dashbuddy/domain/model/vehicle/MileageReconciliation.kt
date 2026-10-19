package cloud.trotter.dashbuddy.domain.model.vehicle

/** The reading span may include personal driving. Other miles are a figure, not an error. */
data class ReconciledMileage(val carMiles: Int, val trackedMiles: Double, val since: Long) {
    val otherMiles: Double get() = carMiles - trackedMiles
}

object MileageReconciliation {
    fun latest(readings: List<VehicleOdometerReading>, vehicleId: String): ReconciledMileage? {
        val pair = readings.filter { it.vehicleId == vehicleId }
            .sortedWith(VehicleOdometerReading.NEWEST_FIRST).take(2).map { it.reading }
        if (pair.size < 2) return null
        val (later, earlier) = pair
        val tracked = (later.gpsTotalMiles ?: return null) - (earlier.gpsTotalMiles ?: return null)
        if (!tracked.isFinite() || tracked < 0 || later.readingMiles < earlier.readingMiles) return null
        return ReconciledMileage(later.readingMiles - earlier.readingMiles, tracked, earlier.occurredAt)
    }
}
