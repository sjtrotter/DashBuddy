package cloud.trotter.dashbuddy.domain.census

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** ADR-0011 transport projection, before the local marker scan and request signing. */
object EnvelopeProjection {
    private val fingerprintPattern = Regex("[0-9a-f]{64}")
    const val MAX_BYTES = 262_144

    /** Malformed or oversized input is refused without exposing its contents or throwing. */
    fun project(envelopeJson: String, fingerprint: String): String? = try {
        require(fingerprintPattern.matches(fingerprint))
        val envelope = Json.parseToJsonElement(envelopeJson) as? JsonObject
            ?: error("object_required")
        require(envelope["schemaId"] == JsonPrimitive("uinode.v1"))
        val timestamp = envelope["timestamp"] as? JsonPrimitive ?: error("timestamp_required")
        require(!timestamp.isString)
        val ts = timestamp.longOrNull ?: error("timestamp_invalid")
        val projected = envelope.toMutableMap()
        envelope["metadata"]?.let {
            val metadata = (it as? JsonObject ?: error("metadata_invalid")).toMutableMap()
            metadata.remove("deviceFingerprint")
            metadata.remove("rulesetSignature")
            projected["metadata"] = JsonObject(metadata)
        }
        projected["timestamp"] = JsonPrimitive(ts - ts % 3_600_000)
        projected["fingerprint"] = JsonPrimitive(fingerprint)
        JsonObject(projected).toString().takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    } catch (_: Throwable) {
        null
    }
}
