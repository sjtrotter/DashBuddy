package cloud.trotter.log.gig.domain.model.vehicle

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/** Decoding never mutates storage; GarageRepository writes v2 only after an explicit successful edit. */
object VehicleStorage {
    const val VERSION = 2
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    private val serializer = ListSerializer(Vehicle.serializer())

    fun encode(vehicles: List<Vehicle>): String = buildJsonObject {
        put("version", VERSION)
        put("vehicles", json.encodeToJsonElement(serializer, vehicles))
    }.toString()

    fun decode(raw: String?, onFailure: (Exception) -> Unit = {}): List<Vehicle>? = try {
        if (raw.isNullOrBlank()) emptyList() else {
            val root = json.parseToJsonElement(raw)
            val version = (root as? JsonObject)?.get("version")?.jsonPrimitive?.int ?: 1
            require(version in 1..VERSION) { "Unsupported garage version" }
            val list = if (root is JsonArray) root else root.jsonObject.getValue("vehicles").jsonArray
            if (version == VERSION) json.decodeFromJsonElement(serializer, list)
            else list.map { migrate(it.jsonObject) }
        }
    } catch (error: Exception) {
        onFailure(error)
        null
    }

    private fun migrate(raw: JsonObject): Vehicle {
        val profileJson = raw["profile"]?.jsonObject ?: buildJsonObject {}
        val legacy = json.decodeFromJsonElement(LegacyProfile.serializer(), profileJson)
        val profileKeys = LegacyProfile.serializer().descriptor.let { d ->
            (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        }
        val vehicleKeys = setOf("id", "name", "createdAt", "profile", "retiredAt")
        // Keep namespaces separate: a root key named "profile.foo" must not overwrite profile.foo.
        // v1 did not own a legacyExtras field, so preserve even that unknown value as-is.
        val extras = raw.filterKeys { it !in vehicleKeys }.toMutableMap()
        val profileExtras = profileJson.filterKeys { key ->
            key !in profileKeys ||
                (key == "mpg" && legacy.vehicleClass in setOf(VehicleClass.EV, VehicleClass.E_BIKE)) ||
                (key == "fuelType" && legacy.vehicleClass in setOf(VehicleClass.EV, VehicleClass.E_BIKE))
        }
        if (profileExtras.isNotEmpty()) extras["profile"] = JsonObject(profileExtras)
        return Vehicle(
            id = raw.getValue("id").jsonPrimitive.content,
            name = raw["name"]?.jsonPrimitive?.contentOrNull,
            createdAt = raw.getValue("createdAt").jsonPrimitive.long,
            profile = legacy.toTyped(),
            retiredAt = raw["retiredAt"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
            legacyExtras = extras,
        )
    }
}
