package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.ui.test.*
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the HUD fixture's paused Compose clock and injected wall clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class DashVehicleHeadTest : HudScreenFixture() {
    private val vehicle = Vehicle("malibu", "Malibu", 0)

    @Test fun `live names the dash vehicle beside the platform`() {
        show({ state(dashVehicle = vehicle) })
        val platform = compose.onNodeWithText("DoorDash").assertIsDisplayed().getBoundsInRoot()
        val car = compose.onNodeWithText(" · Malibu").assertIsDisplayed().getBoundsInRoot()
        assertTrue(car.left >= platform.right)
        assertEquals(platform.top, car.top)
    }
    @Test fun `resting hides dash vehicle even when supplied to the mapper`() {
        show({ state(resting = true, dashVehicle = vehicle) })
        compose.onNodeWithText(" · Malibu").assertDoesNotExist()
    }
    @Test fun `offer retains the dash vehicle`() {
        show({ state(flow = Flow.OfferPresented, dashVehicle = vehicle) })
        compose.onNodeWithText(" · Malibu").assertIsDisplayed()
    }
    @Test fun `log hides dash vehicle`() {
        show({ state(logOpen = true, dashVehicle = vehicle) })
        compose.onNodeWithText(" · Malibu").assertDoesNotExist()
    }
    @Test fun `platform switcher leaves room for dash vehicle`() {
        show({ state(dashVehicle = vehicle).copy(switchable = listOf(Platform.DoorDash, Platform.Uber)) })
        compose.onNodeWithText(" · Malibu").assertIsDisplayed()
    }
}
