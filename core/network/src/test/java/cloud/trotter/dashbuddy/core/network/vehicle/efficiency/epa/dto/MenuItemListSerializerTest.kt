package cloud.trotter.dashbuddy.core.network.vehicle.efficiency.epa.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MenuItemListSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes a JSON array of menu items normally`() {
        val input = """[{"text":"2020","value":"2020"},{"text":"2021","value":"2021"}]"""
        val items = json.decodeFromString(MenuItemListSerializer, input)

        assertEquals(2, items.size)
        assertEquals("2020", items[0].text)
        assertEquals("2021", items[1].text)
    }

    @Test
    fun `wraps a single JSON object into a one-element list`() {
        val input = """{"text":"2020","value":"2020"}"""
        val items = json.decodeFromString(MenuItemListSerializer, input)

        assertEquals(1, items.size)
        assertEquals("2020", items[0].text)
        assertEquals("2020", items[0].value)
    }

    @Test
    fun `deserializes empty array to empty list`() {
        val items = json.decodeFromString(MenuItemListSerializer, "[]")
        assertEquals(0, items.size)
    }
}
