package cloud.trotter.dashbuddy.domain.model.vehicle

import kotlinx.serialization.Serializable

@Serializable
data class Vehicle(val id: String, val name: String, val createdAt: Long) {
    init {
        require(id.isNotBlank())
        require(name == name.trim() && name.length in 1..30)
    }
}

/** A car comparison, never a measurement or a session anchor. */
data class LastOdometerReading(
    val readingMiles: Int,
    val gpsTotalMiles: Double?,
    val occurredAt: Long,
)

data class VehicleOdometerReading(
    val vehicleId: String,
    val reading: LastOdometerReading,
    val sequenceId: Long,
) {
    companion object {
        val NEWEST_FIRST: Comparator<VehicleOdometerReading> =
            compareByDescending<VehicleOdometerReading> { it.reading.occurredAt }
                .thenByDescending { it.sequenceId }

        fun latestFor(readings: List<VehicleOdometerReading>, vehicleId: String): LastOdometerReading? =
            readings.filter { it.vehicleId == vehicleId }.minWithOrNull(NEWEST_FIRST)?.reading
    }
}
