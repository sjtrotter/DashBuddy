package cloud.trotter.log.gig.ui.bubble

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.model.vehicle.*
import kotlinx.coroutines.flow.MutableStateFlow
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
@Config(sdk = [35])
class OdometerHudCapabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bike = Vehicle("bike", "Bike", 1, Bicycle())
    private val garage = MutableStateFlow<Garage?>(Garage(listOf(bike), bike.id, true))
    private val prompts = MutableStateFlow(true)
    private val now = 1_700_000_000_000L

    private fun show() {
        val vm: BubbleViewModel = mock()
        whenever(vm.garage).thenReturn(garage)
        whenever(vm.odometerLoaded).thenReturn(MutableStateFlow(true))
        whenever(vm.odometerPrompts).thenReturn(prompts)
        whenever(vm.odometerReadings).thenReturn(MutableStateFlow(listOf(
            VehicleOdometerReading(bike.id, LastOdometerReading(100, 10.0, now - 7_200_000), 1),
            VehicleOdometerReading(bike.id, LastOdometerReading(110, 16.0, now - 3_600_000), 2),
        )))
        whenever(vm.stamps).thenReturn(MutableStateFlow(emptyList()))
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)) {
                    val hud = OdometerHud(vm, rememberOdometerEntryState(), closeLog = {}, onAddVehicle = {})
                    Column {
                        if (hud.Cue(startedAt = null, endedAt = now - 1_000)) Text("Resting cue")
                        if (hud.Cue(startedAt = now - 60_000, endedAt = null)) Text("Start cue")
                        hud.RestingFooter()
                    }
                }
            }
        }
    }

    private fun assertHidden() {
        compose.onNodeWithText("Resting cue").assertDoesNotExist()
        compose.onNodeWithText("Start cue").assertDoesNotExist()
        compose.onNodeWithText("Bike 110 mi,", substring = true).assertDoesNotExist()
        compose.onNodeWithText("odometer 10 mi, tracked", substring = true).assertDoesNotExist()
    }

    private fun assertShown() {
        compose.onNodeWithText("Resting cue").assertIsDisplayed()
        compose.onNodeWithText("Start cue").assertIsDisplayed()
        compose.onNodeWithText("Bike 110 mi,", substring = true).assertIsDisplayed()
        compose.onNodeWithText("odometer 10 mi, tracked", substring = true).assertIsDisplayed()
    }

    @Test fun `active bicycle hides the footer comparison and both cues`() {
        show()
        assertHidden()
    }

    @Test fun `active bicycle with its switch on shows the footer comparison and both cues`() {
        garage.value = Garage(listOf(bike.copy(odometerShown = true)), bike.id, true)
        show()
        assertShown()
    }

    @Test fun `stored switch changes update footer and cues and prompts only affect cues`() {
        show()
        assertHidden()
        compose.runOnIdle { garage.value = Garage(listOf(bike.copy(odometerShown = true)), bike.id, true) }
        assertShown()
        compose.runOnIdle { prompts.value = false }
        compose.onNodeWithText("Resting cue").assertDoesNotExist()
        compose.onNodeWithText("Start cue").assertDoesNotExist()
        compose.onNodeWithText("Bike 110 mi,", substring = true).assertIsDisplayed()
        compose.runOnIdle { garage.value = Garage(listOf(bike.copy(odometerShown = false)), bike.id, true) }
        assertHidden()
    }
}
