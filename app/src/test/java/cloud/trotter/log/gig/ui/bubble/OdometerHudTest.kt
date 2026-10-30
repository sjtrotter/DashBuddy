package cloud.trotter.log.gig.ui.bubble

import androidx.compose.runtime.mutableStateOf
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class OdometerHudTest {
    private val civic = Vehicle("civic", "Civic", 0)

    @Test fun `openSheet is inert while the garage or readings are loading`() {
        for (garage in listOf(null, Garage(emptyList(), null, readable = true))) {
            val vm: BubbleViewModel = mock()
            whenever(vm.odometerLoaded).thenReturn(MutableStateFlow(false))
            whenever(vm.garage).thenReturn(MutableStateFlow(garage))
            val entry = OdometerEntryState(mutableStateOf(false), mutableStateOf("52"))
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
            val entry = OdometerEntryState(mutableStateOf(false), mutableStateOf("52"))
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

    @Test fun `a garage with vehicles opens reading once even without an active vehicle`() {
        for (activeId in listOf(null, civic.id)) {
            val vm: BubbleViewModel = mock()
            whenever(vm.odometerLoaded).thenReturn(MutableStateFlow(true))
            whenever(vm.garage).thenReturn(MutableStateFlow(Garage(listOf(civic), activeId, readable = true)))
            val entry = OdometerEntryState(mutableStateOf(false), mutableStateOf(""))
            var adds = 0
            val hud = OdometerHud(vm, entry, closeLog = {}, onAddVehicle = { adds++ })

            hud.openSheet()
            entry.digit(5)
            hud.openSheet()

            assertTrue(hud.open)
            assertEquals(5, entry.reading)
            assertEquals(0, adds)
            verify(vm, times(1)).reopenOdometer()
        }
    }
}
