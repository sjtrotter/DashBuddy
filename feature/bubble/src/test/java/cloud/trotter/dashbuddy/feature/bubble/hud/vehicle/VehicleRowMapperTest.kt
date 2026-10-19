package cloud.trotter.dashbuddy.feature.bubble.hud.vehicle

import org.junit.Assert.*
import org.junit.Test

class VehicleRowMapperTest {
    @Test fun `loading garage has no row`() {
        assertNull(VehicleRowMapper.map(null))
    }

    @Test fun `unreadable garage hides vehicles and actions`() {
        assertEquals(VehicleRowModel(null, null, canSwap = false, unreadable = true),
            VehicleRowMapper.map(VehicleRowInput("Civic", "Car", anyVehicles = true, readable = false)))
    }

    @Test fun `empty garage cannot swap`() {
        assertEquals(VehicleRowModel(null, null, canSwap = false, unreadable = false),
            VehicleRowMapper.map(VehicleRowInput(null, null, anyVehicles = false, readable = true)))
    }

    @Test fun `active vehicle supplies the app-resolved name and its type`() {
        assertEquals(VehicleRowModel("Civic", "Car", canSwap = true, unreadable = false),
            VehicleRowMapper.map(VehicleRowInput("Civic", "Car", anyVehicles = true, readable = true)))
    }

    @Test fun `saved vehicles without an active one can still swap, and carry no type`() {
        assertEquals(VehicleRowModel(null, null, canSwap = true, unreadable = false),
            VehicleRowMapper.map(VehicleRowInput(null, "Car", anyVehicles = true, readable = true)))
    }
}
