package cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa.dto

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.serializer

object MenuItemListSerializer : JsonTransformingSerializer<List<MenuItem>>(
    serializer<List<MenuItem>>()
) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        // If the government API returns a single object instead of an array...
        if (element is JsonObject) {
            // ...wrap it in an array for them!
            return buildJsonArray { add(element) }
        }
        return element
    }
}