package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.feature.bubble.hud.vehicle.VehicleRowModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class HudVehicleRowTest : HudScreenFixture() {
    private val car = VehicleRowModel("Civic", "Car", canSwap = true, unreadable = false)

    @Test fun `resting vehicle shows its name and type and routes swap and add`() {
        var swaps = 0
        var adds = 0
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    HudScreen(state(resting = true).copy(vehicle = car), null, {},
                        onSwapVehicle = { swaps++ }, onAddVehicle = { adds++ })
                }
            }
        }
        settle()
        compose.onNodeWithTag("hud.vehicle.row").assertIsDisplayed()
        compose.onNodeWithText("Civic").assertIsDisplayed()
        compose.onNodeWithText("Car").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.swap").assertIsDisplayed().performClick()
        compose.onNodeWithTag("hud.vehicle.add").assertIsDisplayed().performClick()
        assertEquals(1, swaps)
        assertEquals(1, adds)
    }

    @Test fun `loading garage has no resting vehicle row`() {
        show({ state(resting = true) })
        settle()
        compose.onNodeWithTag("hud.vehicle.row").assertDoesNotExist()
    }

    @Test fun `first run shows the vehicle before any dash or reading`() {
        show({ state(first = true).copy(vehicle = car) })
        settle()
        compose.onNodeWithTag("hud.vehicle.row").assertIsDisplayed()
        compose.onNodeWithText("Civic").assertIsDisplayed()
    }

    @Test fun `no vehicle offers add without swap`() {
        show({ state(resting = true).copy(vehicle = VehicleRowModel(null, null, false, false)) })
        settle()
        compose.onNodeWithText("No vehicle").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.add").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.swap").assertDoesNotExist()
    }

    @Test fun `unreadable garage shows the shared warning without actions`() {
        show({ state(resting = true).copy(vehicle = VehicleRowModel(null, null, false, true)) })
        settle()
        compose.onNodeWithText("Saved vehicles could not be read.").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.swap").assertDoesNotExist()
        compose.onNodeWithTag("hud.vehicle.add").assertDoesNotExist()
    }

    @Test fun `vehicle row stays out of live offer and log modes`() {
        var mode by mutableStateOf(HudMode.LIVE)
        var logOpen by mutableStateOf(false)
        show({ state().copy(mode = mode, logOpen = logOpen, vehicle = car) })
        for (next in listOf(HudMode.LIVE, HudMode.OFFER, HudMode.LOG)) {
            compose.runOnIdle { mode = next }
            settle()
            compose.onNodeWithTag("hud.vehicle.row").assertDoesNotExist()
        }
        compose.runOnIdle { mode = HudMode.RESTING; logOpen = true }
        settle()
        compose.onNodeWithTag("hud.vehicle.row").assertDoesNotExist()
    }
}
