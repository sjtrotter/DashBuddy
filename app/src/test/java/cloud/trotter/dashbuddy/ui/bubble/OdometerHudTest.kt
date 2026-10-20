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
                mutableStateOf("52"))
            var closes = 0
            val hud = OdometerHud(vm, entry, closeLog = { closes++ }, onAddVehicle = {})

            hud.openVehicles()

            assertFalse(hud.open)
            assertEquals(OdometerPage.READING, entry.page)
            assertEquals(52, entry.reading)
            assertEquals(0, closes)
            verify(vm, never()).reopenOdometer()
        }
    }

    @Test fun `openSheet is inert while the garage or readings are loading`() {
        for (garage in listOf(null, Garage(emptyList(), null, readable = true))) {
            val vm: BubbleViewModel = mock()
            whenever(vm.odometerLoaded).thenReturn(MutableStateFlow(false))
            whenever(vm.garage).thenReturn(MutableStateFlow(garage))
            val entry = OdometerEntryState(mutableStateOf(false), mutableStateOf(OdometerPage.READING), mutableStateOf("52"))
            var closes = 0
            var adds = 0
            val hud = OdometerHud(vm, entry, closeLog = { closes++ }, onAddVehicle = { adds++ })

            hud.openSheet()

            assertFalse(hud.open)
            assertEquals(52, entry.reading)
            assertEquals(0, closes)
            assertEquals(0, adds)
            verify(vm, never()).reopenOdometer()
        }
    }

    @Test fun `an empty or entirely retired garage opens Add vehicle and leaves the sheet closed`() {
        for (vehicles in listOf(emptyList(), listOf(civic.copy(retiredAt = 1)))) {
            val vm: BubbleViewModel = mock()
            whenever(vm.odometerLoaded).thenReturn(MutableStateFlow(true))
            whenever(vm.garage).thenReturn(MutableStateFlow(Garage(vehicles, null, readable = true)))
            val entry = OdometerEntryState(mutableStateOf(false), mutableStateOf(OdometerPage.READING), mutableStateOf("52"))
            var closes = 0
            var adds = 0
            val hud = OdometerHud(vm, entry, closeLog = { closes++ }, onAddVehicle = { adds++ })

            hud.openSheet()

            assertEquals(1, adds)
            assertEquals(1, closes)
            assertFalse(hud.open)
            assertEquals(52, entry.reading)
            verify(vm, never()).reopenOdometer()
        }
    }

    @Test fun `a garage with vehicles keeps reading and switcher entry behavior`() {
        for (activeId in listOf(null, civic.id)) {
            val vm: BubbleViewModel = mock()
            whenever(vm.odometerLoaded).thenReturn(MutableStateFlow(true))
            whenever(vm.garage).thenReturn(MutableStateFlow(Garage(listOf(civic), activeId, readable = true)))
            val entry = OdometerEntryState(mutableStateOf(false), mutableStateOf(OdometerPage.READING), mutableStateOf(""))
            var adds = 0
            val hud = OdometerHud(vm, entry, closeLog = {}, onAddVehicle = { adds++ })

            hud.openSheet()
            entry.digit(5)
            hud.openSheet()

            assertTrue(hud.open)
            assertEquals(if (activeId == null) OdometerPage.VEHICLES else OdometerPage.READING, entry.page)
            assertEquals(5, entry.reading)
            assertEquals(0, adds)
            verify(vm, times(1)).reopenOdometer()
        }
    }

    @Test fun `loaded switcher closes the log and reopens only once`() {
        val vm: BubbleViewModel = mock()
        whenever(vm.odometerLoaded).thenReturn(MutableStateFlow(true))
        val entry = OdometerEntryState(mutableStateOf(false), mutableStateOf(OdometerPage.READING), mutableStateOf(""))
        var closes = 0
        val hud = OdometerHud(vm, entry, closeLog = { closes++ }, onAddVehicle = {})

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
