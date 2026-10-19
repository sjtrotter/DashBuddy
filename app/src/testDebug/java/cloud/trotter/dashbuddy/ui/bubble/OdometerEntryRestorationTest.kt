package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OdometerEntryRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `restored row switcher still closes on Back and selection`() {
        val restoration = StateRestorationTester(compose)
        lateinit var entry: OdometerEntryState
        restoration.setContent { entry = rememberOdometerEntryState() }
        compose.runOnIdle { entry.showVehicles() }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertTrue(entry.open)
            entry.back(hasVehicle = true)
            assertFalse(entry.open)
            entry.showVehicles()
            entry.digit(5)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertTrue(entry.open)
            assertEquals(5, entry.reading)
            entry.vehicleSwitched()
            assertFalse(entry.open)
            assertNull(entry.reading)
        }
    }
}
