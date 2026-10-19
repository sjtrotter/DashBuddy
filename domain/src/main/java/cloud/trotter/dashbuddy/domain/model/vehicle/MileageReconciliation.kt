package cloud.trotter.dashbuddy.domain.model.vehicle

/** The reading span may include personal driving. Other miles are a figure, not an error. */
data class ReconciledMileage(val carMiles: Int, val trackedMiles: Double) {
    val otherMiles: Double get() = carMiles - trackedMiles
}

object MileageReconciliation {
    fun between(
        readings: List<VehicleOdometerReading>,
        vehicleId: String,
        startedAt: Long,
        endedAt: Long,
    ): ReconciledMileage? {
        if (endedAt < startedAt) return null
        val car = readings.filter { it.vehicleId == vehicleId }.map { it.reading }
        val start = car.filter { it.occurredAt <= startedAt }.maxByOrNull { it.occurredAt } ?: return null
        val end = car.filter { it.occurredAt >= endedAt }.minByOrNull { it.occurredAt } ?: return null
        val tracked = (end.gpsTotalMiles ?: return null) - (start.gpsTotalMiles ?: return null)
        // A reset GPS store cannot describe this span.
        if (!tracked.isFinite() || tracked < 0) return null
        return ReconciledMileage(end.readingMiles - start.readingMiles, tracked)
    }
}
