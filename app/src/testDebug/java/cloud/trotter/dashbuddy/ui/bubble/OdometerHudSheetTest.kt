package cloud.trotter.dashbuddy.ui.bubble

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerPage
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerWriteFailure
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OdometerHudSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `switcher Add calls the app callback and a new garage vehicle appears without changing pages`() {
        val civic = Vehicle("civic", "Civic", 0)
        val garage = MutableStateFlow<Garage?>(Garage(listOf(civic), civic.id, readable = true))
        val vm: BubbleViewModel = mock()
        whenever(vm.garage).thenReturn(garage)
        whenever(vm.odometerReadings).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.gpsTotalMiles).thenReturn(MutableStateFlow(null))
        whenever(vm.odometerSaving).thenReturn(MutableStateFlow(false))
        whenever(vm.odometerWriteFailed).thenReturn(MutableStateFlow(OdometerWriteFailure.NONE))
        whenever(vm.odometerSaveCompleted).thenReturn(MutableSharedFlow())
        whenever(vm.odometerVehicleChanged).thenReturn(MutableSharedFlow())
        val entry = OdometerEntryState(mutableStateOf(true), mutableStateOf(OdometerPage.VEHICLES), mutableStateOf("52"))
        var adds = 0
        val hud = OdometerHud(vm, entry, closeLog = {}, onAddVehicle = { adds++ })
        compose.setContent {
            DashBuddyTheme {
                hud.ObserveEntry()
                Box(Modifier.size(352.dp, 350.dp)) { hud.Sheet() }
            }
        }

        compose.onNodeWithTag("vehicle.add").performClick()
        compose.runOnIdle {
            assertEquals(1, adds)
            assertTrue(hud.open)
            assertEquals(OdometerPage.VEHICLES, entry.page)
            assertEquals(52, entry.reading)
            val bike = Vehicle("bike", "Bike", 1)
            garage.value = Garage(listOf(civic, bike), bike.id, readable = true)
        }
        compose.onNodeWithTag("vehicle.bike").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("vehicle.civic").assertIsDisplayed().assertIsNotSelected()
        compose.runOnIdle {
            assertTrue(hud.open)
            assertEquals(OdometerPage.VEHICLES, entry.page)
            assertEquals(52, entry.reading)
            verify(vm, never()).selectVehicle(any())
            verify(vm, never()).odometerVehicleChangeHandled()
        }
    }
}
