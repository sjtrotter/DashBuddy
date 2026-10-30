package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.model.vehicle.*
import cloud.trotter.log.gig.feature.bubble.hud.vehicle.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class VehicleChipTest : HudScreenFixture() {
    private val car = Vehicle("car", "2020 Honda Civic", 0)
    private val bike = Vehicle("bike", "Bike", 1, EBike())
    private val model = VehicleChipModel(car, listOf(car, bike), car.id, false)
    private var open by mutableStateOf(false)
    private var clicks = 0
    private val selections = mutableListOf<String>()
    private var adds = 0
    private fun chip(model: VehicleChipModel = this.model) {
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    VehicleChip(model, open, { clicks++; open = true }, { open = false },
                        { selections += it; open = false }, { adds++; open = false })
                }
            }
        }
        settle()
    }

    @Test fun `type icon full description capped name and touch target`() {
        chip()
        compose.onNodeWithText("2020 Honda …").assertIsDisplayed()
        compose.onNodeWithContentDescription("Car", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("hud.vehicle.chip").assertContentDescriptionContains("2020 Honda Civic, change vehicle")
            .assertHasClickAction().assertHeightIsAtLeast(48.dp)
    }
    @Test fun `no vehicle has a menu and change description`() {
        chip(model.copy(shown = null, vehicles = emptyList(), activeId = null))
        compose.onNodeWithText("No vehicle").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.chip").assertContentDescriptionContains("No vehicle, change vehicle").performClick()
        settle()
        compose.onNodeWithTag("hud.vehicle.menu").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.divider").assertDoesNotExist()
        compose.onNodeWithTag("hud.vehicle.add").assertIsDisplayed().performClick()
        settle()
        assertEquals(1, adds)
        // The dropdown leaves the tree only after its exit frames run one by one.
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }; compose.onNodeWithTag("hud.vehicle.menu").assertDoesNotExist()
    }
    @Test fun `unavailable has no click action or menu`() {
        chip(VehicleChipModel(null, emptyList(), null, true))
        compose.onNodeWithText("Unavailable").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.chip").assertHasNoClickAction()
            .assertContentDescriptionContains("Unavailable, change vehicle")
        compose.onNodeWithTag("hud.vehicle.chip").performTouchInput { click() }
        settle()
        assertEquals(0, clicks)
        compose.onNodeWithTag("hud.vehicle.menu").assertDoesNotExist()
    }
    @Test fun `menu full names type icons active check divider selection and add`() {
        chip()
        compose.onNodeWithTag("hud.vehicle.chip").performClick(); settle()
        compose.onNodeWithTag("hud.vehicle.menu").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.option.car").assertIsSelected()
        compose.onNodeWithText("2020 Honda Civic").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.check.car", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("hud.vehicle.option.bike").assertIsNotSelected()
        compose.onNodeWithContentDescription("E-bike", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("hud.vehicle.check.bike", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("hud.vehicle.divider").assertExists()
        compose.onNodeWithText("Add vehicle…").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.option.bike").performClick(); settle()
        assertEquals(listOf("bike"), selections)
        // The dropdown leaves the tree only after its exit frames run one by one.
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }; compose.onNodeWithTag("hud.vehicle.menu").assertDoesNotExist()
        compose.onNodeWithTag("hud.vehicle.chip").performClick(); settle()
        compose.onNodeWithTag("hud.vehicle.add").performClick(); settle()
        assertEquals(1, adds)
        assertEquals(listOf("bike"), selections)
    }

}
