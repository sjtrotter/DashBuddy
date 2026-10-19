package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.mutableStateOf
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerPage
import org.junit.Assert.*
import org.junit.Test

class OdometerEntryStateTest {
    private fun entry() = OdometerEntryState(mutableStateOf(false), mutableStateOf(OdometerPage.READING), mutableStateOf(""))

    @Test fun `back from add vehicle returns to the origin page`() {
        for (origin in listOf(OdometerPage.READING, OdometerPage.VEHICLES)) {
            val state = entry()
            state.show(hasVehicle = true)
            state.page = origin
            state.digit(5)
            state.showAddVehicle()
            assertEquals(OdometerPage.ADD_VEHICLE, state.page)
            state.back(hasVehicle = true)
            assertTrue(state.open)
            assertEquals(origin, state.page)
            assertEquals(5, state.reading)
        }
    }

    @Test fun `show while open keeps the digits and page`() {
        val state = entry()
        state.show(hasVehicle = true)
        state.digit(5)
        state.digit(2)
        state.page = OdometerPage.VEHICLES
        state.vehicleName = "Civic"
        state.show(hasVehicle = true)
        assertTrue(state.open)
        assertEquals(OdometerPage.VEHICLES, state.page)
        assertEquals(52, state.reading)
        assertEquals("Civic", state.vehicleName)
    }

    @Test fun `show reports whether it opened`() {
        for (hasVehicle in listOf(false, true)) {
            val state = entry()
            assertTrue(state.show(hasVehicle))
            assertFalse(state.show(hasVehicle))
            state.open = false
            assertTrue(state.show(hasVehicle))
        }
    }

    @Test fun `a repeated add vehicle keeps the first origin`() {
        for (origin in listOf(OdometerPage.READING, OdometerPage.VEHICLES)) {
            val state = entry()
            state.show(hasVehicle = true)
            state.page = origin
            state.showAddVehicle()
            state.showAddVehicle()
            assertEquals(OdometerPage.ADD_VEHICLE, state.page)
            state.back(hasVehicle = true)
            assertTrue(state.open)
            assertEquals(origin, state.page)
        }
    }
    @Test fun `a vehicle added from the reading page keeps the digits`() {
        val state = entry()
        state.show(hasVehicle = true)
        state.digit(5)
        state.digit(2)
        state.showAddVehicle()
        state.vehicleAdded()
        assertTrue(state.open)
        assertEquals(OdometerPage.READING, state.page)
        assertEquals(52, state.reading)
    }

    @Test fun `a vehicle added from the switcher clears the digits`() {
        val state = entry()
        state.show(hasVehicle = true)
        state.digit(5)
        state.digit(2)
        state.page = OdometerPage.VEHICLES
        state.showAddVehicle()
        state.vehicleAdded()
        assertTrue(state.open)
        assertEquals(OdometerPage.READING, state.page)
        assertNull(state.reading)
    }

    @Test fun `the name draft clears after an add`() {
        for (origin in listOf(OdometerPage.READING, OdometerPage.VEHICLES)) {
            val state = entry()
            state.show(hasVehicle = true)
            state.page = origin
            state.showAddVehicle()
            state.vehicleName = "Civic"
            state.vehicleAdded()
            assertEquals("", state.vehicleName)
        }
    }

    @Test fun `a fresh show clears the name draft`() {
        val state = entry()
        state.show(hasVehicle = false)
        state.vehicleName = "Civic"
        state.open = false
        state.show(hasVehicle = false)
        assertEquals("", state.vehicleName)
    }

    @Test fun `a switched vehicle clears the digits`() {
        val state = entry()
        state.show(hasVehicle = true)
        state.digit(5)
        state.digit(2)
        state.page = OdometerPage.VEHICLES
        state.vehicleSwitched()
        assertTrue(state.open)
        assertEquals(OdometerPage.READING, state.page)
        assertNull(state.reading)
    }

}
