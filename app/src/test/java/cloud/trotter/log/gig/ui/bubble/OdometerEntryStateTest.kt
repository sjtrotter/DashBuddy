package cloud.trotter.log.gig.ui.bubble

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test

class OdometerEntryStateTest {
    private fun entry() = OdometerEntryState(mutableStateOf(false), mutableStateOf(""))

    @Test fun `show opens a fresh reading and reports opening once`() {
        val state = entry()
        state.digit(5)
        assertTrue(state.show(anyVehicles = true))
        assertTrue(state.open)
        assertNull(state.reading)
        state.digit(5); state.digit(2)
        assertFalse(state.show(anyVehicles = true))
        assertEquals(52, state.reading)
    }
    @Test fun `empty garage leaves the draft closed and unchanged`() {
        val state = entry()
        state.digit(5)
        assertFalse(state.show(anyVehicles = false))
        assertFalse(state.open)
        assertEquals(5, state.reading)
    }
    @Test fun `back closes and preserves digits until next opening`() {
        val state = entry()
        state.show(true); state.digit(5); state.back()
        assertFalse(state.open)
        assertEquals(5, state.reading)
        assertTrue(state.show(true))
        assertNull(state.reading)
    }
    @Test fun `vehicle switch clears digits and keeps the reading open`() {
        val state = entry()
        state.show(true); state.digit(5); state.vehicleSwitched()
        assertTrue(state.open)
        assertNull(state.reading)
    }
    @Test fun `vehicle switch while closed does not reopen`() {
        val state = entry()
        state.digit(5); state.vehicleSwitched()
        assertFalse(state.open)
        assertNull(state.reading)
    }
}
