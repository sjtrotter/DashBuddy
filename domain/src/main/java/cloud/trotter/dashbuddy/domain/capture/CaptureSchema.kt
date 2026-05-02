package cloud.trotter.dashbuddy.domain.capture

/**
 * Describes the serialization format for a captured raw payload.
 * Each pipeline declares which schema its raw payloads conform to.
 *
 * Implementations handle round-trip (de)serialization so capture
 * envelopes are self-describing and portable to the matchers repo.
 *
 * @param T The raw payload type.
 */
interface CaptureSchema<T> {
    val schemaId: String

    fun serialize(payload: T): String
    fun deserialize(json: String): T
}
