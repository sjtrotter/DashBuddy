package cloud.trotter.dashbuddy.domain.model.vehicle

/** The reading span may include personal driving. Other miles are a figure, not an error. */
data class ReconciledMileage(val carMiles: Int, val trackedMiles: Double) {
    val otherMiles: Double get() = carMiles - trackedMiles
}

object MileageReconciliation {
    const val START_WINDOW_MS = 600_000L

    fun between(
        readings: List<VehicleOdometerReading>,
        vehicleId: String,
        startedAt: Long,
        endedAt: Long,
    ): ReconciledMileage? {
        if (endedAt < startedAt) return null
        val car = readings.filter { it.vehicleId == vehicleId }.map { it.reading }
        val start = car.filter { it.occurredAt < endedAt && it.occurredAt - startedAt <= START_WINDOW_MS }
            .minWithOrNull(compareBy<LastOdometerReading> { kotlin.math.abs(it.occurredAt - startedAt) }
                .thenBy { it.occurredAt }) ?: return null
        val end = car.filter { it.occurredAt >= endedAt }.minByOrNull { it.occurredAt } ?: return null
        val tracked = (end.gpsTotalMiles ?: return null) - (start.gpsTotalMiles ?: return null)
        // A reset GPS store or decreasing car reading cannot describe this span.
        if (!tracked.isFinite() || tracked < 0 || end.readingMiles < start.readingMiles) return null
        return ReconciledMileage(end.readingMiles - start.readingMiles, tracked)
    }
}
