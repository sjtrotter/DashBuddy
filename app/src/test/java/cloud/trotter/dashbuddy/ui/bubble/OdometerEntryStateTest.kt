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
        state.show(hasVehicle = true)
        assertTrue(state.open)
        assertEquals(OdometerPage.VEHICLES, state.page)
        assertEquals(52, state.reading)
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
    @Test fun `an added vehicle keeps the digits`() {
        val state = entry()
        state.show(hasVehicle = true)
        state.digit(5)
        state.digit(2)
        state.showAddVehicle()
        state.vehicleChanged()
        assertTrue(state.open)
        assertEquals(OdometerPage.READING, state.page)
        assertEquals(52, state.reading)
        state.page = OdometerPage.VEHICLES
        state.vehicleChanged()
        assertEquals(OdometerPage.READING, state.page)
        assertNull(state.reading)
    }

    @Test fun `a switched vehicle clears the digits`() {
        val state = entry()
        state.show(hasVehicle = true)
        state.digit(5)
        state.digit(2)
        state.page = OdometerPage.VEHICLES
        state.vehicleChanged()
        assertTrue(state.open)
        assertEquals(OdometerPage.READING, state.page)
        assertNull(state.reading)
    }

    @Test fun `a switch after cancelling an add clears the digits`() {
        val state = entry()
        state.show(hasVehicle = true)
        state.digit(5)
        state.page = OdometerPage.VEHICLES
        state.showAddVehicle()
        state.back(hasVehicle = true)
        state.vehicleChanged()
        assertEquals(OdometerPage.READING, state.page)
        assertNull(state.reading)
    }
}
