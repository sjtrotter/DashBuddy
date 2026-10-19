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
class DockTest {
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
    private var mode by mutableStateOf(HudMode.LIVE)
    private var picked: DockKind? = null
    @Test fun `odometer entry present while dashing and resting`() {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    Box(Modifier.width(352.dp)) { Dock(DockMapper.map(mode, odometerLoaded = true)) { picked = it } }
                }
            }
        }
        settle()
        compose.onNodeWithTag("hud.dock.ODOMETER").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(DockKind.ODOMETER, picked)
        compose.runOnIdle { mode = HudMode.RESTING }; settle()
        compose.onNodeWithTag("hud.dock.ODOMETER").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("hud.dock.SETTINGS").assertIsDisplayed()
    }

}
