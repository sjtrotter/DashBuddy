package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.feature.bubble.MutableClock
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.*
import org.junit.Assert.*
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VehicleSwitcherTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clock = MutableClock(1_700_000_000_000L)
    private lateinit var originalLocale: Locale
    @Before fun setup() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        compose.mainClock.autoAdvance = false
    }
    @After fun cleanup() { Locale.setDefault(originalLocale) }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
    private val vehicles = listOf(Vehicle("civic", "Civic", 0), Vehicle("truck", "Truck", 0))
    private var active by mutableStateOf("civic")
    private var name by mutableStateOf("Civic")
    private fun show(saving: Boolean = false) {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    Box(Modifier.width(352.dp).height(350.dp)) {
                        VehicleSwitcher(vehicles, active, saving, { active = it }, {}, {})
                    }
                }
            }
        }
        settle()
    }
    @Test fun `active vehicle marked`() {
        show()
        compose.onNodeWithTag("vehicle.civic").assertIsSelected()
        compose.onNodeWithTag("vehicle.truck").assertIsNotSelected()
        compose.onNodeWithText("Active").assertIsDisplayed()
    }
    @Test fun `add vehicle is the last row`() {
        show()
        val add = compose.onNodeWithTag("vehicle.add").getBoundsInRoot()
        vehicles.forEach { assertTrue(compose.onNodeWithTag("vehicle.${it.id}").getBoundsInRoot().bottom <= add.top) }
    }
    @Test fun `picking a row makes it active`() {
        show()
        compose.onNodeWithTag("vehicle.truck").performClick(); settle()
        assertEquals("truck", active)
        compose.onNodeWithTag("vehicle.truck").assertIsSelected()
        compose.onNodeWithTag("vehicle.civic").assertIsNotSelected()
    }

    @Test fun `rows and add are disabled while saving`() {
        show(saving = true)
        vehicles.forEach { compose.onNodeWithTag("vehicle.${it.id}").assertIsNotEnabled() }
        compose.onNodeWithTag("vehicle.add").assertIsNotEnabled()
        compose.onNodeWithTag("vehicle.truck").performClick(); settle()
        assertEquals("civic", active)
    }

    @Test fun `a name over the cap is not accepted`() {
        compose.setContent {
            DashBuddyTheme {
                Box(Modifier.width(352.dp).height(350.dp)) {
                    AddVehicleForm(name, { name = it }, enabled = true, {}, {})
                }
            }
        }
        settle()
        compose.onNodeWithTag("vehicle.name").performTextReplacement("x".repeat(31)); settle()
        assertEquals("Civic", name)
        val atCap = "x".repeat(29) + "🚗"
        compose.onNodeWithTag("vehicle.name").performTextReplacement(atCap); settle()
        assertEquals(atCap, name)
        compose.onNodeWithTag("vehicle.name").performTextReplacement(atCap + "x"); settle()
        assertEquals(atCap, name)
        compose.onNodeWithTag("vehicle.name").assertTextContains(atCap)
    }

}
