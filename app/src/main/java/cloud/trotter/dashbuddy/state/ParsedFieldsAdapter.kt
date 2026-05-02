package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.domain.state.ParsedFields
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * Gson adapter for the [ParsedFields] sealed class hierarchy.
 * Stores a discriminator field `"_type"` so the correct subtype
 * is reconstructed during deserialization.
 */
class ParsedFieldsAdapter : JsonSerializer<ParsedFields>, JsonDeserializer<ParsedFields> {

    override fun serialize(
        src: ParsedFields,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        val obj = context.serialize(src, src::class.java) as JsonObject
        obj.addProperty("_type", src::class.simpleName)
        return obj
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): ParsedFields {
        val obj = json.asJsonObject
        val typeName = obj.get("_type")?.asString ?: return ParsedFields.None

        val clazz: Class<out ParsedFields> = when (typeName) {
            "None" -> return ParsedFields.None
            "IdleFields" -> ParsedFields.IdleFields::class.java
            "OfferFields" -> ParsedFields.OfferFields::class.java
            "TaskFields" -> ParsedFields.TaskFields::class.java
            "PostTaskFields" -> ParsedFields.PostTaskFields::class.java
            "SessionEndedFields" -> ParsedFields.SessionEndedFields::class.java
            "PausedFields" -> ParsedFields.PausedFields::class.java
            "TimelineFields" -> ParsedFields.TimelineFields::class.java
            "RatingsFields" -> ParsedFields.RatingsFields::class.java
            "SensitiveFields" -> ParsedFields.SensitiveFields::class.java
            "ClickFields" -> ParsedFields.ClickFields::class.java
            else -> return ParsedFields.None
        }

        return context.deserialize(obj, clazz)
    }
}
