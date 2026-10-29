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
import cloud.trotter.dashbuddy.feature.bubble.hud.vehicle.*
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
@Config(sdk = [35], qualifiers = "w400dp-h800dp") // tall host: no performScrollTo under the frozen clock (see build gotchas)
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
    @Test
    fun `reading block rests against Save with slack above its title and keypad`() {
        show(Modifier.width(400.dp).height(760.dp))
        val sheet = compose.onNodeWithTag("odometer.sheet").getBoundsInRoot()
        val title = compose.onNodeWithText("Odometer").assertIsDisplayed().getBoundsInRoot()
        val firstKey = compose.onNodeWithTag("keypad.1").assertIsDisplayed().assertWidthIsEqualTo(60.dp).getBoundsInRoot()
        val lastKey = compose.onNodeWithTag("keypad.backspace").assertIsDisplayed().getBoundsInRoot()
        val save = compose.onNodeWithTag("odometer.save").assertIsDisplayed().getBoundsInRoot()
        // Odometer keeps its title beside the keypad, so slack is measured from the sheet top.
        val gapAbove = firstKey.top - sheet.top - 8.dp
        val gapBelow = save.top - lastKey.bottom
        assertEquals("Keypad must touch Save", 0.dp, gapBelow)
        assertTrue("Slack belongs above the whole reading block", gapAbove > gapBelow)
        assertTrue("Title must move down with the keypad", title.top - sheet.top - 8.dp > gapBelow)
    }

    @Test
    fun `Save stays reachable in a short frame while the reading and keypad scroll`() {
        show(Modifier.requiredSize(352.dp, 200.dp))
        val save = compose.onNodeWithTag("odometer.save")
            .assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            .assertWidthIsEqualTo(328.dp).assertHeightIsEqualTo(48.dp)
        val bounds = save.getBoundsInRoot()
        compose.onNodeWithTag("keypad.backspace").assertExists()
        compose.onNodeWithTag("odometer.reading").assertExists().assertTextEquals("52,388")
        compose.onNodeWithTag("keypad.block").assert(hasScrollAction())
        assertEquals(bounds, save.assertIsDisplayed().getBoundsInRoot())
        save.performTouchInput { click() }
        assertEquals(1, saved)
    }

    @Test fun `an unnamed vehicle uses its type label in the title row and first-reading copy`() {
        val bike = Vehicle("bike", null, 0, EBike())
        model = model.copy(vehicle = bike, vehicles = listOf(bike), check = OdometerCheck.First(null))
        show()
        compose.onNodeWithText("E-bike").assertIsDisplayed()
        compose.onNodeWithText("First reading for E-bike").assertIsDisplayed()
    }

    private var saved = 0
    private var addOpened = false
    private fun show(hostModifier: Modifier = Modifier.width(352.dp).height(350.dp)) {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    Column(hostModifier) {
                        OdometerSheet(model, onDigit = {}, onBackspace = {}, onSave = { saved++ },
                            vehicleChip = { VehicleChip(VehicleChipModel(model.vehicle, model.vehicles, model.vehicle?.id, false),
                                false, {}, {}, {}, {}) },
                            onShowAddVehicle = { addOpened = true }, onBack = {})
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
        compose.onNodeWithText("Waiting for tracked miles").assertIsDisplayed()
        assertEquals(0, saved)
        compose.runOnIdle { model = model.copy(gpsLoaded = true) }; settle()
        compose.onNodeWithText("Waiting for tracked miles").assertDoesNotExist()
        compose.onNodeWithTag("odometer.save").assertIsEnabled().performClick()
        assertEquals(1, saved)
    }
    @Test fun `gps waiting label appears only when gps is the remaining save blocker`() {
        model = model.copy(gpsLoaded = false, readingMiles = null, check = OdometerCheck.Empty)
        show()
        compose.onNodeWithText("Waiting for tracked miles").assertDoesNotExist()
        compose.runOnIdle { model = model.copy(readingMiles = 52_388, saving = true) }; settle()
        compose.onNodeWithText("Waiting for tracked miles").assertDoesNotExist()
        compose.runOnIdle { model = model.copy(saving = false, vehicle = null, check = OdometerCheck.NoVehicle) }; settle()
        compose.onNodeWithText("Waiting for tracked miles").assertDoesNotExist()
        compose.runOnIdle { model = model.copy(vehicle = car, check = OdometerCheck.First(car.identityName)) }; settle()
        compose.onNodeWithText("Waiting for tracked miles").assertIsDisplayed()
    }

    @Test fun `point key disabled`() {
        show()
        compose.onNodeWithTag("keypad.point").assertIsNotEnabled()
        compose.onNodeWithTag("keypad.backspace").assertIsEnabled()
    }

    @Test fun `an unreadable garage says so`() {
        model = model.copy(vehicle = null, vehicles = emptyList(), check = OdometerCheck.NoVehicle,
            writeFailed = OdometerWriteFailure.UNREADABLE)
        show()
        compose.onNodeWithText("Couldn't load your vehicles.").assertIsDisplayed()
        compose.onNodeWithText("Could not save. Try again.").assertDoesNotExist()
    }

    @Test fun `fewer than tracked shows add vehicle link`() {
        model = model.copy(check = OdometerCheck.FewerThanTracked(12, 57.0, clock.millis))
        show()
        compose.onNodeWithText("12 mi on the odometer, 57.0 mi tracked since ${formatShortDate(clock.millis)}").assertIsDisplayed()
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

}
