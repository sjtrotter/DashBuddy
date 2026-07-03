package cloud.trotter.dashbuddy.domain.model.event

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #314 — [EventMetadata] gained `@Serializable` so the analytics projector can
 * decode device metadata with kotlinx. The historical rows were written by Gson
 * (`DashBuddyApplication.createMetadata`), so kotlinx must parse both the Gson
 * field shape and the test-mode `{"test_mode": true}` shape without throwing.
 */
class EventMetadataSerializationTest {

    // Mirrors AppEventRepo's metadata reader: unknown keys tolerated (the Gson
    // test-mode row carries a key the class doesn't have).
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round-trips a fully populated metadata object`() {
        val original = EventMetadata(
            odometer = 12345.6,
            batteryLevel = 82,
            networkType = "WIFI",
            appVersion = "1.2.3",
        )
        val decoded = json.decodeFromString<EventMetadata>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `parses the Gson field shape written on device`() {
        // Exactly what com.google.gson.Gson().toJson(EventMetadata(...)) emits.
        val gsonShape = """{"odometer":42.0,"batteryLevel":57,"networkType":"UNKNOWN","appVersion":"1.0.5"}"""
        val decoded = json.decodeFromString<EventMetadata>(gsonShape)
        assertEquals(42.0, decoded.odometer!!, 0.0)
        assertEquals(57, decoded.batteryLevel)
        assertEquals("UNKNOWN", decoded.networkType)
        assertEquals("1.0.5", decoded.appVersion)
    }

    @Test
    fun `parses the historical test-mode row via ignoreUnknownKeys`() {
        // DashBuddyApplication.createMetadata() returns this before Hilt is up.
        val testMode = """{ "test_mode": true }"""
        val decoded = json.decodeFromString<EventMetadata>(testMode)
        // Unknown key ignored; every field falls back to its default (null).
        assertNull(decoded.odometer)
        assertNull(decoded.batteryLevel)
        assertNull(decoded.networkType)
        assertNull(decoded.appVersion)
    }

    @Test
    fun `parses a partial row where Gson omitted null fields`() {
        // Gson omits nulls by default, so an odometer-only stamp is legal.
        val partial = """{"odometer":7.5}"""
        val decoded = json.decodeFromString<EventMetadata>(partial)
        assertEquals(7.5, decoded.odometer!!, 0.0)
        assertNull(decoded.batteryLevel)
    }
}
