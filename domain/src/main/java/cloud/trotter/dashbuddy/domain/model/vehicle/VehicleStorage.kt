package cloud.trotter.dashbuddy.domain.model.vehicle

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object VehicleStorage {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Vehicle.serializer())
    fun encode(vehicles: List<Vehicle>): String = json.encodeToString(serializer, vehicles)
    fun decode(raw: String?): List<Vehicle> = try {
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString(serializer, raw)
    } catch (_: Exception) {
        emptyList()
    }
}
