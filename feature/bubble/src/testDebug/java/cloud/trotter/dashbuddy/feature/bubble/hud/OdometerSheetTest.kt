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
class OdometerSheetTest {
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
    private val car = Vehicle("civic", "Civic", 0)
    private var model by mutableStateOf(OdometerSheetModel(car, listOf(car), 52_388, null, OdometerCheck.First("Civic")))
    private var saved = 0
    private var added: String? = null
    private var addOpened = false
    private fun show() {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    Column(Modifier.width(352.dp).height(350.dp)) {
                        OdometerSheet(model, {}, {}, { saved++ }, { added = it }, {}, {}, { addOpened = true }, {})
                    }
                }
            }
        }
        settle()
    }
    @Test fun `save states the typed reading`() {
        show()
        compose.onNodeWithText("Save 52,388 mi").assertIsDisplayed().performClick()
        assertEquals(1, saved)
    }
    @Test fun `save disabled while empty`() {
        model = model.copy(readingMiles = null, check = OdometerCheck.Empty)
        show()
        compose.onNodeWithTag("odometer.save").assertIsNotEnabled()
    }
    @Test fun `point key disabled`() {
        show()
        compose.onNodeWithTag("keypad.point").assertIsNotEnabled()
        compose.onNodeWithTag("keypad.backspace").assertIsEnabled()
    }
    @Test fun `no vehicle shows add vehicle form`() {
        model = model.copy(vehicle = null, vehicles = emptyList(), check = OdometerCheck.NoVehicle)
        show()
        compose.onNodeWithTag("vehicle.name").assertIsDisplayed()
        compose.onNodeWithTag("odometer.save").assertDoesNotExist()
    }
    @Test fun `fewer than tracked shows add vehicle link`() {
        model = model.copy(check = OdometerCheck.FewerThanTracked(12, 84.0, clock.millis))
        show()
        compose.onNodeWithText("Add vehicle").assertIsDisplayed().performClick()
        assertTrue(addOpened)
    }
    @Test fun `warning still allows save`() {
        model = model.copy(check = OdometerCheck.Lower(52_400))
        show()
        compose.onNodeWithTag("odometer.save").assertIsEnabled().performClick()
        assertEquals(1, saved)
        compose.runOnIdle { model = model.copy(check = OdometerCheck.LargeJump(469_260, clock.millis)) }; settle()
        compose.onNodeWithTag("odometer.save").assertIsEnabled()
        compose.runOnIdle { model = model.copy(check = OdometerCheck.FewerThanTracked(12, 84.0, clock.millis)) }; settle()
        compose.onNodeWithTag("odometer.save").assertIsEnabled()
    }
    @Test fun `add vehicle invokes callback with trimmed name`() {
        model = model.copy(page = OdometerPage.ADD_VEHICLE)
        show()
        compose.onNodeWithTag("vehicle.name").performTextInput("  Civic  "); settle()
        compose.onNodeWithText("Add Civic").assertExists()
        compose.onNodeWithTag("vehicle.save").performClick()
        assertEquals("Civic", added)
    }

}
