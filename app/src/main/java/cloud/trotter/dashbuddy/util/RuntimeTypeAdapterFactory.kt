package cloud.trotter.dashbuddy.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.internal.Streams
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException

/**
 * Adapts values whose runtime type may differ from their declaration type. This
 * is necessary when a field's type is a single interface or class, but the
 * value is actually an instance of one of several implementations.
 */
class RuntimeTypeAdapterFactory<T> private constructor(
    private val baseType: Class<T>,
    private val typeFieldName: String,
    private val maintainType: Boolean
) : TypeAdapterFactory {

    private val labelToSubtype = LinkedHashMap<String, Class<*>>()
    private val subtypeToLabel = LinkedHashMap<Class<*>, String>()

    fun registerSubtype(
        type: Class<out T>,
        label: String = type.simpleName
    ): RuntimeTypeAdapterFactory<T> {
        if (subtypeToLabel.containsKey(type) || labelToSubtype.containsKey(label)) {
            throw IllegalArgumentException("types and labels must be unique")
        }
        labelToSubtype[label] = type
        subtypeToLabel[type] = label
        return this
    }

    override fun <R> create(gson: Gson, type: TypeToken<R>): TypeAdapter<R>? {
        if (type.rawType != baseType) {
            return null
        }

        val labelToDelegate = LinkedHashMap<String, TypeAdapter<*>>()
        val subtypeToDelegate = LinkedHashMap<Class<*>, TypeAdapter<*>>()

        for ((label, subtype) in labelToSubtype) {
            val delegate = gson.getDelegateAdapter(this, TypeToken.get(subtype))
            labelToDelegate[label] = delegate
            subtypeToDelegate[subtype] = delegate
        }

        @Suppress("UNCHECKED_CAST")
        return object : TypeAdapter<R>() {
            @Throws(IOException::class)
            override fun read(`in`: JsonReader): R {
                val jsonElement = Streams.parse(`in`)
                val labelJsonElement = if (maintainType) {
                    jsonElement.asJsonObject.get(typeFieldName)
                } else {
                    jsonElement.asJsonObject.remove(typeFieldName)
                }

                if (labelJsonElement == null) {
                    throw JsonParseException("cannot deserialize $baseType because it does not define a field named $typeFieldName")
                }
                val label = labelJsonElement.asString
                val delegate = labelToDelegate[label] as TypeAdapter<R>?
                    ?: throw JsonParseException("cannot deserialize $baseType subtype named $label; did you forget to register a subtype?")
                return delegate.fromJsonTree(jsonElement)
            }

            @Throws(IOException::class)
            override fun write(out: JsonWriter, value: R) {
                val srcType = value!!::class.java
                val label = subtypeToLabel[srcType]
                val delegate = subtypeToDelegate[srcType] as TypeAdapter<R>?
                if (delegate == null) {
                    throw JsonParseException("cannot serialize ${srcType.name}; did you forget to register a subtype?")
                }
                val jsonObject = delegate.toJsonTree(value).asJsonObject
                if (maintainType) {
                    Streams.write(jsonObject, out)
                    return
                }
                val clone = JsonObject()
                if (jsonObject.has(typeFieldName)) {
                    throw JsonParseException("cannot serialize ${srcType.name} because it already defines a field named $typeFieldName")
                }
                clone.add(typeFieldName, JsonPrimitive(label))
                for ((key, elem) in jsonObject.entrySet()) {
                    clone.add(key, elem)
                }
                Streams.write(clone, out)
            }
        }.nullSafe()
    }

    companion object {
        fun <T> of(
            baseType: Class<T>,
            typeFieldName: String = "type"
        ): RuntimeTypeAdapterFactory<T> {
            return RuntimeTypeAdapterFactory(baseType, typeFieldName, false)
        }
    }
}