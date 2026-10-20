package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.ui.test.*
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.feature.bubble.hud.vehicle.VehicleChipModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the HUD fixture's paused Compose clock and injected wall clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class DashVehicleHeadTest : HudScreenFixture() {
    private val vehicle = Vehicle("malibu", "Malibu", 0)
    private val chip = VehicleChipModel(vehicle, listOf(vehicle), vehicle.id, false)

    @Test fun `live puts chip at the right end of the head`() {
        show({ state(vehicleChip = chip) })
        val platform = compose.onNodeWithText("DoorDash").assertIsDisplayed().getBoundsInRoot()
        val car = compose.onNodeWithTag("hud.vehicle.chip").assertIsDisplayed().getBoundsInRoot()
        assertTrue(car.left >= platform.right)
        assertEquals(((platform.top + platform.bottom) / 2).value, ((car.top + car.bottom) / 2).value, 1f)
        val dock = compose.onNodeWithTag("hud.dock").getBoundsInRoot()
        assertEquals(dock.right, car.right)
        compose.onNodeWithText(" · Malibu").assertDoesNotExist()
    }
    @Test fun `paused sits beside platform before the chip`() {
        show({ state(paused = true, vehicleChip = chip) })
        val platform = compose.onNodeWithText("DoorDash").getBoundsInRoot()
        val paused = compose.onNodeWithText("paused").assertIsDisplayed().getBoundsInRoot()
        val car = compose.onNodeWithTag("hud.vehicle.chip").getBoundsInRoot()
        assertTrue(paused.left >= platform.right) // the " · " separator sits between them
        assertEquals(platform.top, paused.top)
        assertTrue(paused.right <= car.left)
    }
    @Test fun `resting retains header chip and has no vehicle row`() {
        show({ state(resting = true, vehicleChip = chip) })
        compose.onNodeWithTag("hud.vehicle.chip").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.row").assertDoesNotExist()
    }
    @Test fun `offer retains header chip`() {
        show({ state(flow = Flow.OfferPresented, vehicleChip = chip) })
        compose.onNodeWithTag("hud.vehicle.chip").assertIsDisplayed()
    }
    @Test fun `log retains header chip`() {
        show({ state(logOpen = true, vehicleChip = chip) })
        compose.onNodeWithTag("hud.vehicle.chip").assertIsDisplayed()
    }
    @Test fun `platform switcher leaves room for chip`() {
        show({ state(vehicleChip = chip).copy(switchable = listOf(Platform.DoorDash, Platform.Uber)) })
        compose.onNodeWithTag("hud.vehicle.chip").assertIsDisplayed()
    }
}
