package cloud.trotter.dashbuddy.domain.model.vehicle

/** Car miles may include personal driving; tracked miles use dash attribution when covered. Other miles are not an error. */
data class ReconciledMileage(val carMiles: Int, val trackedMiles: Double, val since: Long) {
    val otherMiles: Double get() = carMiles - trackedMiles
}

/** Compares readings using per-vehicle dash miles, falling back to the original GPS-span rules outside coverage. */
object MileageReconciliation {
    /** Uncovered spans still withhold when another vehicle has a reading inside the span. */
    fun latest(
        readings: List<VehicleOdometerReading>, vehicleId: String, dashes: List<DashVehicle> = emptyList(),
    ): ReconciledMileage? {
        val pair = readings.filter { it.vehicleId == vehicleId }
            .sortedWith(VehicleOdometerReading.NEWEST_FIRST).take(2).map { it.reading }
        if (pair.size < 2) return null
        val (later, earlier) = pair
        val from = earlier.gpsTotalMiles ?: return null
        val to = later.gpsTotalMiles ?: return null
        val originalTracked = to - from
        if (!originalTracked.isFinite() || originalTracked < 0 || later.readingMiles < earlier.readingMiles) return null
        val attributed = DashVehicles.milesFor(dashes, vehicleId, from, to)
        if (attributed == null && !VehicleOdometerReading.singleVehicleSpan(readings, vehicleId, earlier.occurredAt, later.occurredAt)) return null
        return ReconciledMileage(later.readingMiles - earlier.readingMiles, attributed ?: originalTracked, earlier.occurredAt)
    }
}
