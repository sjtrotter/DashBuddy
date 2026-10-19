package cloud.trotter.dashbuddy.domain.model.vehicle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Vehicle(
    val id: String,
    val name: String?,
    val createdAt: Long,
    val profile: VehicleProfile = GasCar(),
    val retiredAt: Long? = null,
    val legacyExtras: Map<String, JsonElement> = emptyMap(),
) {
    init {
        require(id.isNotBlank())
        require(name == null || (name == name.trim() && name.codePointCount(0, name.length) in 1..MAX_NAME_CODE_POINTS))
    }

    val identityName: String? get() = name ?: listOfNotNull(profile.year, profile.make, profile.model)
        .filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }

    companion object {
        const val MAX_NAME_CODE_POINTS = 30
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

        fun singleVehicleSpan(readings: List<VehicleOdometerReading>, vehicleId: String, from: Long, to: Long): Boolean =
            readings.none { it.vehicleId != vehicleId && it.reading.occurredAt > from && it.reading.occurredAt <= to }
    }
}
