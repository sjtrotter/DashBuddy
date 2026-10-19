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
import cloud.trotter.dashbuddy.domain.format.formatShortDate
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
    private var model by mutableStateOf(OdometerSheetModel(car, listOf(car), 52_388, null, OdometerCheck.First("Civic"), gpsLoaded = true))
    private var saved = 0
    private var added: String? = null
    private var addOpened = false
    private var visible by mutableStateOf(true)
    private fun show() {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    Column(Modifier.width(352.dp).height(350.dp)) {
                        if (visible) OdometerSheet(model, { model = model.copy(vehicleName = it) },
                            {}, {}, { saved++ }, { added = it }, {}, {}, { addOpened = true }, {}, {})
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
    @Test fun `save is disabled without a vehicle`() {
        model = model.copy(vehicle = null, vehicles = emptyList(), check = OdometerCheck.NoVehicle)
        show()
        compose.onNodeWithTag("odometer.save").assertIsNotEnabled()
        assertEquals(0, saved)
    }
    @Test fun `save waits for the gps total`() {
        model = model.copy(gpsLoaded = false)
        show()
        compose.onNodeWithTag("odometer.save").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Waiting for the GPS total").assertIsDisplayed()
        assertEquals(0, saved)
        compose.runOnIdle { model = model.copy(gpsLoaded = true) }; settle()
        compose.onNodeWithText("Waiting for the GPS total").assertDoesNotExist()
        compose.onNodeWithTag("odometer.save").assertIsEnabled().performClick()
        assertEquals(1, saved)
    }
    @Test fun `gps waiting label appears only when gps is the remaining save blocker`() {
        model = model.copy(gpsLoaded = false, readingMiles = null, check = OdometerCheck.Empty)
        show()
        compose.onNodeWithText("Waiting for the GPS total").assertDoesNotExist()
        compose.runOnIdle { model = model.copy(readingMiles = 52_388, saving = true) }; settle()
        compose.onNodeWithText("Waiting for the GPS total").assertDoesNotExist()
        compose.runOnIdle { model = model.copy(saving = false, vehicle = null, check = OdometerCheck.NoVehicle) }; settle()
        compose.onNodeWithText("Waiting for the GPS total").assertDoesNotExist()
        compose.runOnIdle { model = model.copy(vehicle = car, check = OdometerCheck.First(car.displayName)) }; settle()
        compose.onNodeWithText("Waiting for the GPS total").assertIsDisplayed()
    }
    @Test fun `add is disabled while the garage is unreadable`() {
        model = model.copy(vehicle = null, vehicles = emptyList(), check = OdometerCheck.NoVehicle,
            page = OdometerPage.ADD_VEHICLE, vehicleName = "Civic", writeFailed = OdometerWriteFailure.UNREADABLE)
        show()
        compose.onNodeWithTag("vehicle.name").assertIsNotEnabled()
        compose.onNodeWithTag("vehicle.save").assertIsNotEnabled().performClick()
        assertNull(added)
        compose.runOnIdle { model = model.copy(writeFailed = OdometerWriteFailure.NONE) }; settle()
        compose.onNodeWithTag("vehicle.name").assertIsEnabled()
        compose.onNodeWithTag("vehicle.save").assertIsEnabled().performClick()
        assertEquals("Civic", added)
    }
    @Test fun `point key disabled`() {
        show()
        compose.onNodeWithTag("keypad.point").assertIsNotEnabled()
        compose.onNodeWithTag("keypad.backspace").assertIsEnabled()
    }
    @Test fun `no vehicle shows add vehicle form`() {
        model = model.copy(vehicle = null, vehicles = emptyList(), check = OdometerCheck.NoVehicle, page = OdometerPage.ADD_VEHICLE)
        show()
        compose.onNodeWithTag("vehicle.name").assertIsDisplayed()
        compose.onNodeWithTag("odometer.save").assertDoesNotExist()
    }
    @Test fun `an unreadable garage says so`() {
        model = model.copy(vehicle = null, vehicles = emptyList(), check = OdometerCheck.NoVehicle,
            page = OdometerPage.ADD_VEHICLE, writeFailed = OdometerWriteFailure.UNREADABLE)
        show()
        compose.onNodeWithText("Saved vehicles could not be read.").assertIsDisplayed()
        compose.onNodeWithText("Could not save. Try again.").assertDoesNotExist()
    }
    @Test fun `fewer than tracked shows add vehicle link`() {
        model = model.copy(check = OdometerCheck.FewerThanTracked(12, 57.0, clock.millis))
        show()
        compose.onNodeWithText("12 mi on this car, 57.0 mi tracked since ${formatShortDate(clock.millis)}").assertIsDisplayed()
        compose.onNodeWithText("Add vehicle").assertIsDisplayed().performClick()
        assertTrue(addOpened)
    }
    @Test fun `add vehicle link is disabled while saving`() {
        model = model.copy(check = OdometerCheck.FewerThanTracked(12, 57.0, clock.millis), saving = true)
        show()
        compose.onNodeWithText("Add vehicle").assertIsDisplayed().assertIsNotEnabled().performClick()
        assertFalse(addOpened)
        compose.runOnIdle { model = model.copy(saving = false) }; settle()
        compose.onNodeWithText("Add vehicle").assertIsEnabled().performClick()
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
    @Test fun `large jump says more than expected`() {
        model = model.copy(check = OdometerCheck.LargeJump(469_260, clock.millis))
        show()
        compose.onNodeWithText("+469,260 mi since ${formatShortDate(clock.millis)}, more than expected").assertIsDisplayed()
    }
    @Test fun `last reading and delta use the shared date format`() {
        val since = clock.millis - 400 * 86_400_000L
        model = model.copy(readingMiles = null, last = LastOdometerReading(52_388, 100.0, since), check = OdometerCheck.Empty)
        show()
        compose.onNodeWithText("Last 52,388 mi, ${formatShortDate(since)}").assertIsDisplayed()
        compose.runOnIdle { model = model.copy(readingMiles = 52_400, check = OdometerCheck.Ok(12, since)) }; settle()
        compose.onNodeWithText("+12 mi since ${formatShortDate(since)}").assertIsDisplayed()
    }
    @Test fun `add vehicle invokes callback with trimmed name`() {
        model = model.copy(page = OdometerPage.ADD_VEHICLE)
        show()
        compose.onNodeWithTag("vehicle.name").performTextInput("  Civic  "); settle()
        compose.onNodeWithText("Add Civic").assertExists()
        compose.onNodeWithTag("vehicle.save").performClick()
        assertEquals("Civic", added)
    }

    @Test fun `the vehicle name draft survives the sheet leaving composition`() {
        model = model.copy(page = OdometerPage.ADD_VEHICLE)
        show()
        compose.onNodeWithTag("vehicle.name").performTextInput("Civic"); settle()
        assertEquals("Civic", model.vehicleName)
        compose.runOnIdle { visible = false }; settle()
        compose.onNodeWithTag("vehicle.name").assertDoesNotExist()
        compose.runOnIdle { visible = true }; settle()
        compose.onNodeWithTag("vehicle.name").assertTextContains("Civic")
        compose.onNodeWithTag("vehicle.save").performClick()
        assertEquals("Civic", added)
    }

}
