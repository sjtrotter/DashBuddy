package cloud.trotter.dashbuddy.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleStorageTest {
    @Test fun `round trips`() {
        val vehicles = listOf(Vehicle("one", "Civic", 100), Vehicle("two", "Truck", 200))
        assertEquals(vehicles, VehicleStorage.decode(VehicleStorage.encode(vehicles)))
    }
    @Test fun `malformed blob decodes to empty`() {
        listOf(null, "", "broken", "{}", """[{"id":"one","name":" ","createdAt":0}]""").forEach {
            assertEquals(emptyList<Vehicle>(), VehicleStorage.decode(it))
        }
    }
}
