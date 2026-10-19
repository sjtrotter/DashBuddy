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
        assertTrue(state.showVehicles())
        assertTrue(state.open)
        assertEquals(OdometerPage.VEHICLES, state.page)
        assertNull(state.reading)
    }

    @Test fun `showVehicles while open preserves the page and drafts`() {
        val state = entry()
        assertTrue(state.showVehicles())
        state.page = OdometerPage.READING
        state.digit(5)
        assertFalse(state.showVehicles())
        assertEquals(OdometerPage.READING, state.page)
        assertEquals(5, state.reading)
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

    @Test fun `saved vehicles without an active one open the switcher`() {
        val state = entry()
        assertTrue(state.show(activeVehicle = false, anyVehicles = true))
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

    @Test fun `an empty garage does not open the sheet or change the draft`() {
        val state = entry()
        state.digit(5)
        assertFalse(state.show(activeVehicle = false, anyVehicles = false))
        assertFalse(state.open)
        assertEquals(OdometerPage.READING, state.page)
        assertEquals(5, state.reading)
    }

    @Test fun `show while open keeps the digits and page`() {
        val state = entry()
        state.show(activeVehicle = true, anyVehicles = true)
        state.digit(5)
        state.digit(2)
        state.page = OdometerPage.VEHICLES
        state.show(activeVehicle = true, anyVehicles = true)
        assertTrue(state.open)
        assertEquals(OdometerPage.VEHICLES, state.page)
        assertEquals(52, state.reading)
    }

    @Test fun `show reports whether it opened`() {
        for ((activeVehicle, anyVehicles) in listOf(false to true, true to true)) {
            val state = entry()
            assertTrue(state.show(activeVehicle, anyVehicles))
            assertFalse(state.show(activeVehicle, anyVehicles))
            state.open = false
            assertTrue(state.show(activeVehicle, anyVehicles))
        }
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

    @Test fun `retiring the last active car closes the sheet and keeps digits`() {
        val state = entry()
        state.show(activeVehicle = true, anyVehicles = true)
        state.digit(5)
        state.page = OdometerPage.VEHICLES
        state.vehicleRetired(wasActive = true, activeVehicle = false, anyVehicles = false)
        assertFalse(state.open)
        assertEquals(5, state.reading)
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
            // Removing the last parked car closes the sheet.
            state.vehicleRetired(wasActive = false, activeVehicle = false, anyVehicles = false)
            assertFalse(state.open)
            assertEquals(52, state.reading)
        }
    }
}
