package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.mutableStateOf
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerPage
import org.junit.Assert.*
import org.junit.Test

class OdometerEntryStateTest {
    private fun entry() = OdometerEntryState(mutableStateOf(false), mutableStateOf(OdometerPage.READING), mutableStateOf(""))

    @Test fun `showVehicles opens the switcher and clears drafts`() {
        val state = entry()
        state.digit(5)
        state.vehicleName = "Civic"
        assertTrue(state.showVehicles())
        assertTrue(state.open)
        assertEquals(OdometerPage.VEHICLES, state.page)
        assertNull(state.reading)
        assertEquals("", state.vehicleName)
    }

    @Test fun `showVehicles while open preserves the page and drafts`() {
        val state = entry()
        assertTrue(state.showVehicles())
        state.showAddVehicle()
        state.digit(5)
        state.vehicleName = "Civic"
        assertFalse(state.showVehicles())
        assertEquals(OdometerPage.ADD_VEHICLE, state.page)
        assertEquals(5, state.reading)
        assertEquals("Civic", state.vehicleName)
    }

    @Test fun `back from a row-opened switcher closes with or without an active vehicle`() {
        for (hasVehicle in listOf(false, true)) {
            val state = entry()
            state.showVehicles()
            state.back(hasVehicle)
            assertFalse(state.open)
        }
    }

    @Test fun `a selection from a row-opened switcher clears digits and closes`() {
        val state = entry()
        state.showVehicles()
        state.digit(5)
        state.vehicleSwitched()
        assertNull(state.reading)
        assertFalse(state.open)
    }

    @Test fun `back from a reading-page switcher keeps the existing outcomes`() {
        for (hasVehicle in listOf(false, true)) {
            val state = entry()
            state.show(activeVehicle = true, anyVehicles = true)
            state.digit(5)
            state.page = OdometerPage.VEHICLES
            state.back(hasVehicle)
            assertEquals(hasVehicle, state.open)
            if (hasVehicle) assertEquals(OdometerPage.READING, state.page)
            assertEquals(5, state.reading)
        }
    }

    @Test fun `show resets row origin so a later switcher returns to reading`() {
        val state = entry()
        state.showVehicles()
        state.back(hasVehicle = true)
        state.show(activeVehicle = true, anyVehicles = true)
        state.page = OdometerPage.VEHICLES
        state.back(hasVehicle = true)
        assertTrue(state.open)
        assertEquals(OdometerPage.READING, state.page)
        state.page = OdometerPage.VEHICLES
        state.vehicleSwitched()
        assertTrue(state.open)
        assertEquals(OdometerPage.READING, state.page)
    }

    @Test fun `show while open preserves row origin`() {
        val state = entry()
        state.showVehicles()
        assertFalse(state.show(activeVehicle = true, anyVehicles = true))
        state.back(hasVehicle = true)
        assertFalse(state.open)
    }

    @Test fun `showVehicles while reading preserves reading origin`() {
        val state = entry()
        state.show(activeVehicle = true, anyVehicles = true)
        state.digit(5)
        assertFalse(state.showVehicles())
        state.page = OdometerPage.VEHICLES
        state.back(hasVehicle = true)
        assertTrue(state.open)
        assertEquals(OdometerPage.READING, state.page)
        assertEquals(5, state.reading)
    }

    @Test fun `showVehicles resets add origin to the switcher`() {
        val state = entry()
        state.show(activeVehicle = true, anyVehicles = true)
        state.showAddVehicle()
        state.open = false
        state.showVehicles()
        state.showAddVehicle()
        state.back(hasVehicle = true)
        assertTrue(state.open)
        assertEquals(OdometerPage.VEHICLES, state.page)
        state.back(hasVehicle = true)
        assertFalse(state.open)
    }

    @Test fun `saved vehicles without an active one open the switcher`() {
        val state = entry()
        assertTrue(state.show(activeVehicle = false, anyVehicles = true))
        assertTrue(state.open)
        assertEquals(OdometerPage.VEHICLES, state.page)
        state.showAddVehicle()
        // If an active vehicle becomes available, Back still returns to the original switcher.
        state.back(hasVehicle = true)
        assertTrue(state.open)
        assertEquals(OdometerPage.VEHICLES, state.page)
    }

    @Test fun `back from the switcher without an active vehicle closes the sheet`() {
        val state = entry()
        state.show(activeVehicle = false, anyVehicles = true)
        state.back(hasVehicle = false)
        assertFalse(state.open)
    }

    @Test fun `an active vehicle opens the reading page`() {
        val state = entry()
        state.show(activeVehicle = true, anyVehicles = true)
        assertEquals(OdometerPage.READING, state.page)
    }

    @Test fun `an empty garage opens add vehicle and back closes the sheet`() {
        val state = entry()
        state.show(activeVehicle = false, anyVehicles = false)
        assertEquals(OdometerPage.ADD_VEHICLE, state.page)
        state.back(hasVehicle = false)
        assertFalse(state.open)
    }

    @Test fun `back from add vehicle returns to the origin page`() {
        for (origin in listOf(OdometerPage.READING, OdometerPage.VEHICLES)) {
            val state = entry()
            state.show(activeVehicle = true, anyVehicles = true)
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
        state.show(activeVehicle = true, anyVehicles = true)
        state.digit(5)
        state.digit(2)
        state.page = OdometerPage.VEHICLES
        state.vehicleName = "Civic"
        state.show(activeVehicle = true, anyVehicles = true)
        assertTrue(state.open)
        assertEquals(OdometerPage.VEHICLES, state.page)
        assertEquals(52, state.reading)
        assertEquals("Civic", state.vehicleName)
    }

    @Test fun `show reports whether it opened`() {
        for ((activeVehicle, anyVehicles) in listOf(false to false, false to true, true to true)) {
            val state = entry()
            assertTrue(state.show(activeVehicle, anyVehicles))
            assertFalse(state.show(activeVehicle, anyVehicles))
            state.open = false
            assertTrue(state.show(activeVehicle, anyVehicles))
        }
    }

    @Test fun `a repeated add vehicle keeps the first origin`() {
        for (origin in listOf(OdometerPage.READING, OdometerPage.VEHICLES)) {
            val state = entry()
            state.show(activeVehicle = true, anyVehicles = true)
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
        state.show(activeVehicle = true, anyVehicles = true)
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
        state.show(activeVehicle = true, anyVehicles = true)
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
            state.show(activeVehicle = true, anyVehicles = true)
            state.page = origin
            state.showAddVehicle()
            state.vehicleName = "Civic"
            state.vehicleAdded()
            assertEquals("", state.vehicleName)
        }
    }

    @Test fun `a fresh show clears the name draft`() {
        val state = entry()
        state.show(activeVehicle = false, anyVehicles = false)
        state.vehicleName = "Civic"
        state.open = false
        state.show(activeVehicle = false, anyVehicles = false)
        assertEquals("", state.vehicleName)
    }

    @Test fun `a switched vehicle clears the digits`() {
        val state = entry()
        state.show(activeVehicle = true, anyVehicles = true)
        state.digit(5)
        state.digit(2)
        state.page = OdometerPage.VEHICLES
        state.vehicleSwitched()
        assertTrue(state.open)
        assertEquals(OdometerPage.READING, state.page)
        assertNull(state.reading)
    }

    @Test fun `retiring the active car routes to the surviving garage and keeps digits`() {
        for ((active, remaining, page) in listOf(
            Triple(true, true, OdometerPage.READING),
            Triple(false, true, OdometerPage.VEHICLES),
            Triple(false, false, OdometerPage.ADD_VEHICLE),
        )) {
            val state = entry()
            state.show(activeVehicle = true, anyVehicles = true)
            state.digit(5)
            state.digit(2)
            state.page = OdometerPage.VEHICLES
            state.vehicleRetired(wasActive = true, activeVehicle = active, anyVehicles = remaining)
            assertTrue(state.open)
            assertEquals(page, state.page)
            assertEquals(52, state.reading)
        }
    }

    @Test fun `retiring a parked car keeps the current page and digits`() {
        for (page in OdometerPage.entries) {
            val state = entry()
            state.show(activeVehicle = true, anyVehicles = true)
            state.digit(5)
            state.digit(2)
            state.page = page
            state.vehicleRetired(wasActive = false, activeVehicle = true, anyVehicles = true)
            assertTrue(state.open)
            assertEquals(page, state.page)
            assertEquals(52, state.reading)
            // Removing the last parked car must still route to vehicle creation.
            state.vehicleRetired(wasActive = false, activeVehicle = false, anyVehicles = false)
            assertEquals(OdometerPage.ADD_VEHICLE, state.page)
            assertEquals(52, state.reading)
        }
    }
}
