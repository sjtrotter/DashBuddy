package cloud.trotter.dashbuddy.ui.bubble

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerWriteFailure
import cloud.trotter.dashbuddy.feature.bubble.hud.vehicle.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class OdometerHudSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val civic = Vehicle("civic", "Civic", 0)
    private val bike = Vehicle("bike", "Bike", 1)
    private val changes = MutableSharedFlow<VehicleChange>(extraBufferCapacity = 1)
    private val saves = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val entry = OdometerEntryState(mutableStateOf(true), mutableStateOf("52"))
    private var adds = 0
    private val selections = mutableListOf<String>()
    private val vm: BubbleViewModel = mock()

    private fun show() {
        whenever(vm.garage).thenReturn(MutableStateFlow(Garage(listOf(civic, bike), civic.id, true)))
        whenever(vm.odometerReadings).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.gpsTotalMiles).thenReturn(MutableStateFlow(100.0))
        whenever(vm.odometerSaving).thenReturn(MutableStateFlow(false))
        whenever(vm.odometerWriteFailed).thenReturn(MutableStateFlow(OdometerWriteFailure.NONE))
        whenever(vm.odometerSaveCompleted).thenReturn(saves)
        whenever(vm.odometerVehicleChanged).thenReturn(changes)
        val hud = OdometerHud(vm, entry, closeLog = {}, onAddVehicle = { adds++ })
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)) {
                    var menu by remember { mutableStateOf(false) }
                    hud.ObserveEntry()
                    Box(Modifier.size(400.dp, 500.dp)) {
                        hud.Sheet {
                            VehicleChip(VehicleChipModel(civic, listOf(civic, bike), civic.id, false), menu,
                                { menu = true }, { menu = false }, { selections += it; menu = false },
                                { adds++; menu = false })
                        }
                    }
                }
            }
        }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }

    @Test fun `sheet hosts chip menu and Add callback without a switcher page`() {
        show()
        compose.onNodeWithTag("odometer.reading").assertTextEquals("52")
        compose.onNodeWithTag("hud.vehicle.chip").performClick(); settle()
        compose.onNodeWithTag("hud.vehicle.add").performClick(); settle()
        assertEquals(1, adds)
        assertEquals(52, entry.reading)
        assertTrue(entry.open)
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }; compose.onNodeWithTag("hud.vehicle.menu").assertDoesNotExist()
    }
    @Test fun `successful selection clears reading and keeps sheet open`() {
        show()
        compose.onNodeWithTag("hud.vehicle.chip").performClick(); settle()
        compose.onNodeWithTag("hud.vehicle.option.bike").performClick(); settle()
        assertEquals(listOf(bike.id), selections)
        assertEquals(52, entry.reading)
        compose.runOnIdle { assertTrue(changes.tryEmit(VehicleChange.SWITCHED)) }; settle()
        assertNull(entry.reading)
        assertTrue(entry.open)
        verify(vm, times(1)).odometerVehicleChangeHandled()
    }
    @Test fun `save completion closes reading and is handled once`() {
        show()
        compose.runOnIdle { assertTrue(saves.tryEmit(Unit)) }; settle()
        assertFalse(entry.open)
        verify(vm, times(1)).odometerSaveHandled()
    }
}
