package cloud.trotter.dashbuddy.pipeline.accessibility.model

import android.graphics.Rect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object RectSerializer : KSerializer<Rect> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("android.graphics.Rect", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Rect) {
        // Format: "[left,top][right,bottom]"
        val str = "[${value.left},${value.top}][${value.right},${value.bottom}]"
        encoder.encodeString(str)
    }

    override fun deserialize(decoder: Decoder): Rect {
        val string = decoder.decodeString()
        // Simple regex to parse "[0,0][100,100]"
        val numbers = Regex("-?\\d+").findAll(string).map { it.value.toInt() }.toList()
        return if (numbers.size >= 4) {
            Rect(numbers[0], numbers[1], numbers[2], numbers[3])
        } else {
            Rect()
        }
    }
}