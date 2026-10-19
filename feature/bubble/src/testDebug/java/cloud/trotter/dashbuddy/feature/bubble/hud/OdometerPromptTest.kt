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
class OdometerPromptTest {
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
    private var enabled by mutableStateOf(true)
    private var last by mutableStateOf<LastOdometerReading?>(null)
    private var endedAt by mutableStateOf<Long?>(null)
    private var opened = false
    private val startedAt = clock.millis
    private fun show() {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    OdometerPrompt(enabled, startedAt, endedAt, last) { opened = true }
                }
            }
        }
        settle()
    }
    @Test fun `link shows under ten minutes without a reading`() {
        show()
        compose.onNodeWithText("Log odometer").assertIsDisplayed().performClick()
        assertTrue(opened)
        clock.millis += 599_000
        compose.mainClock.advanceTimeBy(1_000); settle()
        compose.onNodeWithText("Log odometer").assertIsDisplayed()
        clock.millis += 1_000
        compose.mainClock.advanceTimeBy(1_000); settle()
        compose.onNodeWithText("Log odometer").assertDoesNotExist()
    }
    @Test fun `link hides once a reading exists`() {
        show()
        compose.runOnIdle { last = LastOdometerReading(52_388, 100.0, startedAt) }; settle()
        compose.onNodeWithText("Log odometer").assertDoesNotExist()
    }
    @Test fun `pref off hides the link`() {
        enabled = false; show()
        compose.onNodeWithText("Log odometer").assertDoesNotExist()
    }
    @Test fun `resting link lasts until a reading after the dash end`() {
        endedAt = startedAt + 60_000
        clock.millis += 86_400_000
        last = LastOdometerReading(52_000, 0.0, startedAt)
        show()
        compose.onNodeWithText("Log odometer").assertIsDisplayed()
        compose.runOnIdle { last = LastOdometerReading(52_388, 100.0, endedAt!! + 1) }; settle()
        compose.onNodeWithText("Log odometer").assertDoesNotExist()
    }

}
