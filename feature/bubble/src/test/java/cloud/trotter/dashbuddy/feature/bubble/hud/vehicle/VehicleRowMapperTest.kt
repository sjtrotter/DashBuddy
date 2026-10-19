package cloud.trotter.dashbuddy.feature.bubble.hud.vehicle

import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import org.junit.Assert.*
import org.junit.Test

class VehicleRowMapperTest {
    private val civic = Vehicle("civic", "Civic", 0)

    @Test fun `loading garage has no row`() {
        assertNull(VehicleRowMapper.map(null))
    }

    @Test fun `unreadable garage hides vehicles and actions`() {
        assertEquals(VehicleRowModel(null, canSwap = false, unreadable = true),
            VehicleRowMapper.map(VehicleRowInput(civic, switchable = true, readable = false)))
    }

    @Test fun `empty garage cannot swap`() {
        assertEquals(VehicleRowModel(null, canSwap = false, unreadable = false),
            VehicleRowMapper.map(VehicleRowInput(null, switchable = false, readable = true)))
    }

    @Test fun `active vehicle is passed through with supplied swap availability`() {
        for (switchable in listOf(false, true)) {
            assertEquals(VehicleRowModel(civic, canSwap = switchable, unreadable = false),
                VehicleRowMapper.map(VehicleRowInput(civic, switchable, readable = true)))
        }
    }

    @Test fun `saved vehicles without an active one follow supplied swap availability`() {
        for (switchable in listOf(false, true)) {
            assertEquals(VehicleRowModel(null, canSwap = switchable, unreadable = false),
                VehicleRowMapper.map(VehicleRowInput(null, switchable, readable = true)))
        }
    }
}
