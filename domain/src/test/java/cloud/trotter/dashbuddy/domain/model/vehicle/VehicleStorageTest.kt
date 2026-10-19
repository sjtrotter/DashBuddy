package cloud.trotter.dashbuddy.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleStorageTest {
    @Test fun `round trips`() {
        val vehicles = listOf(Vehicle("one", "Civic", 100), Vehicle("two", "Truck", 200))
        assertEquals(vehicles, VehicleStorage.decode(VehicleStorage.encode(vehicles)))
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
