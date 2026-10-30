package cloud.trotter.log.gig.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.feature.bubble.MutableClock
import cloud.trotter.log.gig.feature.bubble.hud.undo.*
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
            GigLogTheme {
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
    @Test fun `a failed undo keeps the strip and retries`() {
        var model by mutableStateOf(UndoStripModel(700, "Panera Bread", 71, clock.millis + 10_000, 1))
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    if (undoStripVisible(model)) UndoStrip(model) {
                        taps++
                        model = model.copy(busy = true, failed = false)
                    }
                }
            }
        }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Undo").assertIsNotEnabled()
        compose.runOnIdle {
            model = model.copy(busy = false, failed = true)
            clock.millis += 10_001
        }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Undo failed. Tap to try again.").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertIsEnabled().performClick()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(32)
        assertEquals(2, taps)
        assertEquals("tip", newestBand(mapOf("tip" to model.token.takeIf { model.visible(clock.millis) })))
        // Another failed append also leaves an enabled retry.
        compose.runOnIdle { model = model.copy(busy = false, failed = true) }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Undo failed. Tap to try again.").assertIsDisplayed()
        compose.onNodeWithText("Undo").assertIsEnabled()
    }
    @Test fun `drain follows Compose frames and host hides exactly at deadline`() {
        show()
        val initial = progress()
        compose.runOnIdle { clock.millis += 5_000 }
        compose.mainClock.advanceTimeBy(5_000)
        val halfway = progress()
        assertTrue(halfway < initial)
        assertTrue(halfway in 0.45f..0.55f)
        compose.runOnIdle { clock.millis += 4_800 }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(4_800)
        compose.onNodeWithTag("undo.strip").assertExists()
        compose.runOnIdle { clock.millis += 200 }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("undo.strip").assertDoesNotExist()
    }
    @Test fun `expense label uses the existing where slot without a delivery fallback`() {
        val model = UndoStripModel(1840, "wipers", 72, clock.millis + 10_000, 2,
            whatLabel = cloud.trotter.log.gig.feature.bubble.R.string.hud_log_cat_car_care, whereFallback = null)
        compose.setContent {
            GigLogTheme { CompositionLocalProvider(LocalNowProvider provides clock) { UndoStrip(model) {} } }
        }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("Car care $18.40, wipers").assertIsDisplayed()
    }
}
