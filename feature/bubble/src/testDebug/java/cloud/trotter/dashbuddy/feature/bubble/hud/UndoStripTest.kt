package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.feature.bubble.MutableClock
import cloud.trotter.dashbuddy.feature.bubble.hud.undo.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UndoStripTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clock = MutableClock(1_700_000_000_000L)
    private lateinit var locale: Locale
    private var taps = 0
    @Before fun setup() { locale = Locale.getDefault(); Locale.setDefault(Locale.US); compose.mainClock.autoAdvance = false }
    @After fun cleanup() { Locale.setDefault(locale) }
    private fun show() {
        val model = UndoStripModel(700, "Panera Bread", 71, clock.millis + 10_000, 1)
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    if (undoStripVisible(model)) UndoStrip(model) { taps++ }
                }
            }
        }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(32)
    }
    private fun progress() = compose.onNodeWithTag("undo.drain").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    @Test fun `copy names what and where and Undo taps once`() {
        show()
        compose.onNodeWithText("Cash tip $7.00, Panera Bread").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Undo").assertIsNotEnabled()
        assertEquals(1, taps)
    }
    @Test fun `drain follows Compose frames and host hides exactly at deadline`() {
        show()
        val initial = progress()
        compose.mainClock.advanceTimeBy(5_000)
        val halfway = progress()
        assertTrue(halfway < initial)
        assertTrue(halfway in 0.45f..0.55f)
        compose.runOnIdle { clock.millis += 9_999 }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("undo.strip").assertExists()
        compose.runOnIdle { clock.millis += 1 }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("undo.strip").assertDoesNotExist()
    }
}
