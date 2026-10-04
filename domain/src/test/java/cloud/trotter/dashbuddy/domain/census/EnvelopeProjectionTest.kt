package cloud.trotter.dashbuddy.domain.census

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EnvelopeProjectionTest {
    private val fingerprint = "ab".repeat(32)
    private fun envelope(timestamp: String = "7200123", extra: String = "") =
        """{"schemaId":"uinode.v1","timestamp":$timestamp,"metadata":{"deviceFingerprint":"device","rulesetSignature":"signature","engineVersion":1},"payload":{"text":"Continue"}$extra}"""

    @Test fun `object projection matches wire projection and leaves byte cap to serializer`() {
        val projected = requireNotNull(EnvelopeProjection.projectToObject(envelope(), fingerprint))
        assertEquals(EnvelopeProjection.project(envelope(), fingerprint), projected.toString())
        val huge = envelope(extra = ",\"padding\":\"${"x".repeat(EnvelopeProjection.MAX_BYTES)}\"")
        assertNotNull(EnvelopeProjection.projectToObject(huge, fingerprint))
        assertNull(EnvelopeProjection.project(huge, fingerprint))
        assertNull(EnvelopeProjection.projectToObject(envelope(), "A".repeat(64)))
        assertNull(EnvelopeProjection.projectToObject("[]", fingerprint))
    }

    @Test fun `projection removes only transport metadata coarsens time adds fingerprint and compacts JSON`() {
        val projected = requireNotNull(EnvelopeProjection.project(envelope(), fingerprint))
        val json = Json.parseToJsonElement(projected).jsonObject
        assertEquals(JsonPrimitive(fingerprint), json["fingerprint"])
        assertEquals(7_200_000L, json.getValue("timestamp").jsonPrimitive.long)
        assertEquals(setOf("engineVersion"), json.getValue("metadata").jsonObject.keys)
        assertEquals(JsonPrimitive("Continue"), json.getValue("payload").jsonObject["text"])
        assertFalse(projected.contains("device"))
        assertFalse(projected.contains("signature"))
        assertFalse(projected.contains("\n"))
        assertEquals(projected, EnvelopeProjection.project(projected, fingerprint))
        assertEquals(projected, EnvelopeProjection.project(" \n ${envelope()} \n", fingerprint))
    }

    @Test fun `schema and object are required and malformed input never throws`() {
        for (input in listOf("", "{", "[]", "null", "42", "{}", envelope().replace("uinode.v1", "uinode.v2"),
            envelope().replace("\"uinode.v1\"", "1"), envelope().replace("\"metadata\":{", "\"metadata\":null,\"other\":{"))) {
            assertNull(input, EnvelopeProjection.project(input, fingerprint))
        }
    }

    @Test fun `fingerprint is exactly 64 lowercase hex and replaces any previous pairing`() {
        for (invalid in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "a".repeat(64) + "\n")) {
            assertNull(EnvelopeProjection.project(envelope(), invalid))
        }
        val json = Json.parseToJsonElement(requireNotNull(EnvelopeProjection.project(
            envelope(extra = ",\"fingerprint\":\"old\""), fingerprint))).jsonObject
        assertEquals(JsonPrimitive(fingerprint), json["fingerprint"])
    }

    @Test fun `timestamp must be integer epoch millis and uses the specified hour remainder`() {
        for (invalid in listOf("null", "true", "\"7200123\"", "1.5", "9223372036854775808", "{}")) {
            assertNull(EnvelopeProjection.project(envelope(invalid), fingerprint))
        }
        assertNull(EnvelopeProjection.project("""{"schemaId":"uinode.v1"}""", fingerprint))
        for (ts in listOf(0L, 3_599_999L, 3_600_000L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val json = Json.parseToJsonElement(requireNotNull(EnvelopeProjection.project(envelope(ts.toString()), fingerprint))).jsonObject
            assertEquals(ts - ts % 3_600_000, json.getValue("timestamp").jsonPrimitive.long)
        }
    }

    @Test fun `absent metadata is allowed and unrelated fields survive`() {
        val json = Json.parseToJsonElement(requireNotNull(EnvelopeProjection.project(
            """{"schemaId":"uinode.v1","timestamp":1,"captureId":"local","payload":[]} """, fingerprint))).jsonObject
        assertFalse(json.containsKey("metadata"))
        assertEquals(JsonPrimitive("local"), json["captureId"])
    }

    @Test fun `cap is inclusive and counts UTF8 output bytes after projection`() {
        fun input(padding: String) = envelope(extra = ",\"padding\":\"$padding\"")
        val overhead = requireNotNull(EnvelopeProjection.project(input(""), fingerprint)).toByteArray(Charsets.UTF_8).size
        val remaining = EnvelopeProjection.MAX_BYTES - overhead
        assertNotNull(EnvelopeProjection.project(input("x".repeat(remaining)), fingerprint))
        assertNull(EnvelopeProjection.project(input("x".repeat(remaining + 1)), fingerprint))
        assertNull(EnvelopeProjection.project(input("é".repeat(remaining)), fingerprint))
        val hugeRemoved = envelope().replace("\"device\"", "\"${"x".repeat(300_000)}\"")
        assertNotNull(EnvelopeProjection.project(hugeRemoved, fingerprint))
    }
}
