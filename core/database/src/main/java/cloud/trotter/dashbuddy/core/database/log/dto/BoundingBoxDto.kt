package cloud.trotter.dashbuddy.core.database.log.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = BoundingBoxDtoSerializer::class)
data class BoundingBoxDto(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

object BoundingBoxDtoSerializer : KSerializer<BoundingBoxDto> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("BoundingBoxDto") {
        element<Int>("left")
        element<Int>("top")
        element<Int>("right")
        element<Int>("bottom")
    }

    override fun deserialize(decoder: Decoder): BoundingBoxDto {
        val jsonDecoder =
            decoder as? JsonDecoder ?: throw IllegalStateException("Only JSON is supported")
        val element = jsonDecoder.decodeJsonElement()

        // 1. Handle the legacy string format from older snapshots: "[0,0][1080,2400]"
        if (element is JsonPrimitive && element.isString) {
            val str = element.content
            val regex = """\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""".toRegex()
            val match = regex.find(str)
            return if (match != null) {
                BoundingBoxDto(
                    left = match.groupValues[1].toInt(),
                    top = match.groupValues[2].toInt(),
                    right = match.groupValues[3].toInt(),
                    bottom = match.groupValues[4].toInt()
                )
            } else {
                BoundingBoxDto(0, 0, 0, 0)
            }
        }

        // 2. Handle the standard Object format going forward: {"left":0, "top":0...}
        if (element is JsonObject) {
            return BoundingBoxDto(
                left = element["left"]?.jsonPrimitive?.int ?: 0,
                top = element["top"]?.jsonPrimitive?.int ?: 0,
                right = element["right"]?.jsonPrimitive?.int ?: 0,
                bottom = element["bottom"]?.jsonPrimitive?.int ?: 0
            )
        }

        return BoundingBoxDto(0, 0, 0, 0)
    }

    override fun serialize(encoder: Encoder, value: BoundingBoxDto) {
        val jsonEncoder =
            encoder as? JsonEncoder ?: throw IllegalStateException("Only JSON is supported")

        // Always write new files as proper JSON Objects instead of Strings
        val obj = buildJsonObject {
            put("left", JsonPrimitive(value.left))
            put("top", JsonPrimitive(value.top))
            put("right", JsonPrimitive(value.right))
            put("bottom", JsonPrimitive(value.bottom))
        }
        jsonEncoder.encodeJsonElement(obj)
    }
}