package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.mutableStateOf
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerPage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class OdometerHudTest {
    private val civic = Vehicle("civic", "Civic", 0)

    @Test fun `openVehicles is inert until both garage and readings are loaded`() {
        // A populated garage with loaded=false represents readings still loading.
        for (garage in listOf(null, Garage(listOf(civic), civic.id, readable = true))) {
            val vm: BubbleViewModel = mock()
            whenever(vm.odometerLoaded).thenReturn(MutableStateFlow(false))
            whenever(vm.garage).thenReturn(MutableStateFlow(garage))
            val entry = OdometerEntryState(mutableStateOf(false), mutableStateOf(OdometerPage.READING),
                mutableStateOf("52"), vehicleNameState = mutableStateOf("Draft"))
            var closes = 0
            val hud = OdometerHud(vm, entry) { closes++ }

            hud.openVehicles()

            assertFalse(hud.open)
            assertEquals(OdometerPage.READING, entry.page)
            assertEquals(52, entry.reading)
            assertEquals("Draft", entry.vehicleName)
            assertEquals(0, closes)
            verify(vm, never()).reopenOdometer()
        }
    }

    @Test fun `loaded switcher closes the log and reopens only once`() {
        val vm: BubbleViewModel = mock()
        whenever(vm.odometerLoaded).thenReturn(MutableStateFlow(true))
        val entry = OdometerEntryState(mutableStateOf(false), mutableStateOf(OdometerPage.READING), mutableStateOf(""))
        var closes = 0
        val hud = OdometerHud(vm, entry) { closes++ }

        hud.openVehicles()

        assertTrue(hud.open)
        assertEquals(OdometerPage.VEHICLES, entry.page)
        assertEquals(1, closes)
        entry.digit(5)
        hud.openVehicles()
        assertEquals(5, entry.reading)
        verify(vm, times(1)).reopenOdometer()
        verify(vm, never()).garage
    }
}
