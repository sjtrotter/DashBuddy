package cloud.trotter.dashbuddy.feature.bubble.hud.vehicle

import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import org.junit.Assert.*
import org.junit.Test

class VehicleChipMapperTest {
    private val civic = Vehicle("civic", "Civic", 0)
    private val bike = Vehicle("bike", "Bike", 1)
    private fun input(live: Boolean = false, dash: Vehicle? = bike, readable: Boolean = true) =
        VehicleChipInput(civic, dash, live, listOf(civic, bike, civic.copy(id = "old", retiredAt = 2)), readable)

    @Test fun `loading means no chip`() { assertNull(VehicleChipMapper.map(null)) }
    @Test fun `unreadable hides all vehicle data`() {
        assertEquals(VehicleChipModel(null, emptyList(), null, true), VehicleChipMapper.map(input(readable = false)))
    }
    @Test fun `at rest shows active vehicle`() { assertEquals(civic, VehicleChipMapper.map(input())!!.shown) }
    @Test fun `live shows dash vehicle including unknown instead of active`() {
        assertEquals(bike, VehicleChipMapper.map(input(live = true))!!.shown)
        assertNull(VehicleChipMapper.map(input(live = true, dash = null))!!.shown)
    }
    @Test fun `menu filters retired and checks active rather than shown vehicle`() {
        val model = VehicleChipMapper.map(input(live = true))!!
        assertEquals(listOf(civic, bike), model.vehicles)
        assertEquals(civic.id, model.activeId)
        assertFalse(model.unavailable)
    }
    /** Review r1: the odometer sheet names the vehicle a reading is saved against, never the dash's. */
    @Test fun `for active names the active vehicle even during a dash in another`() {
        val live = VehicleChipMapper.map(input(live = true, dash = bike))!!
        assertEquals(bike, live.shown)
        assertEquals(civic, live.forActive().shown)
    }
    @Test fun `twelve code points fit and thirteen cap to eleven and ellipsis`() {
        assertEquals("abcdefghijkl", chipName("abcdefghijkl"))
        assertEquals("abcdefghijk…", chipName("abcdefghijklm"))
    }
    @Test fun `emoji are never split`() {
        assertEquals("😀".repeat(12), chipName("😀".repeat(12)))
        assertEquals("😀".repeat(11) + "…", chipName("😀".repeat(13)))
        assertEquals("ab😀…", chipName("ab😀cd", max = 4))
    }
    @Test fun `entry vehicle defaults and stale selections fall back to active`() {
        val old = civic.copy(id = "old", retiredAt = 2)
        val model = VehicleChipMapper.map(input(live = true))!!.copy(vehicles = listOf(civic, bike, old))
        for (id in listOf(null, "unknown", old.id)) {
            assertEquals(civic, model.forEntry(id).shown)
            assertEquals(civic.id, model.forEntry(id).activeId)
        }
        assertEquals(bike, model.forEntry(bike.id).shown)
        assertEquals(bike.id, model.forEntry(bike.id).activeId)
        assertEquals(civic.id, model.activeId)
    }
    @Test fun `entry with no active vehicle or unreadable garage stays empty`() {
        assertNull(VehicleChipModel(null, emptyList(), null, false).forEntry("missing").shown)
        val unavailable = VehicleChipMapper.map(input(readable = false))!!
        assertEquals(unavailable, unavailable.forEntry(bike.id))
    }

}
