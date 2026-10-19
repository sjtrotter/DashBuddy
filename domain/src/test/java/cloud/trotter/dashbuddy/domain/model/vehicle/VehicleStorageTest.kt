package cloud.trotter.dashbuddy.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleStorageTest {
    @Test fun `round trips`() {
        val vehicles = listOf(Vehicle("one", "Civic", 100), Vehicle("two", "Truck", 200))
        assertEquals(vehicles, VehicleStorage.decode(VehicleStorage.encode(vehicles)))
    }
    @Test fun `a thirty code point name with emoji is valid`() {
        val name = "x".repeat(29) + "🚗"
        val vehicle = Vehicle("one", name, 100)
        assertEquals(Vehicle.MAX_NAME_CODE_POINTS, name.codePointCount(0, name.length))
        assertEquals(name, vehicle.name)
        assertEquals(listOf(vehicle), VehicleStorage.decode(VehicleStorage.encode(listOf(vehicle))))
        assertThrows(IllegalArgumentException::class.java) { Vehicle("two", name + "x", 100) }
    }
    @Test fun `malformed json decodes to null`() {
        listOf("broken", "{}", """[{"id":"one","name":" ","createdAt":0}]""",
            """[{"id":"good","name":"Civic","createdAt":0},{"id":"bad","name":" ","createdAt":0}]""").forEach {
            assertNull(VehicleStorage.decode(it))
        }
    }
    @Test fun `absent or blank input is an empty garage`() {
        listOf(null, "", "  ").forEach { assertEquals(emptyList<Vehicle>(), VehicleStorage.decode(it)) }
    }
}
