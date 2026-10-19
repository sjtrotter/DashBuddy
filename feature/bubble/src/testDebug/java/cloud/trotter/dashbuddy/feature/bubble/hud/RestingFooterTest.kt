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
class RestingFooterTest {
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
    private val reading = LastOdometerReading(52_388, 157.3, clock.millis)
    private var last by mutableStateOf<LastOdometerReading?>(reading)
    private var opened = false
    private fun show() {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    OdometerFooter("Civic", last, ReconciledMileage(59, 57.3, reading.occurredAt - 86_400_000)) { opened = true }
                }
            }
        }
        settle()
    }
    @Test fun `shows last reading with vehicle name`() {
        show()
        compose.onNodeWithText("Civic 52,388 mi,", substring = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("car 59 mi, tracked 57.3 mi since ${odometerDate(reading.occurredAt - 86_400_000)}").assertIsDisplayed()
        assertTrue(opened)
    }
    @Test fun `hidden without a reading`() {
        last = null; show()
        compose.onNodeWithText("Civic", substring = true).assertDoesNotExist()
        compose.onNodeWithText("car", substring = true).assertDoesNotExist()
    }

}
