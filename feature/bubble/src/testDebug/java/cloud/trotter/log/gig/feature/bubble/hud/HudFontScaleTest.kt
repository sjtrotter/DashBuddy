package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.theme.LocalGlance
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.feature.bubble.MutableClock
import cloud.trotter.log.gig.feature.bubble.hud.keypad.Keypad
import org.junit.Assert.*
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HudFontScaleTest {
    @get:Rule val compose = createComposeRule()
    private val clock = MutableClock(1_700_000_000_000L)
    private var glance by mutableStateOf(1f)
    private val facts = JobFacts("Store", clock.millis + 240_000, 7.9, 5.4)
    private lateinit var locale: Locale
    @Before fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        compose.mainClock.autoAdvance = false
    }
    @After fun cleanup() { Locale.setDefault(locale) }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
    private fun show(scale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale), LocalNowProvider provides clock) {
                GigLogTheme(glance = glance) {
                    Box(Modifier.width(600.dp).height(900.dp)) {
                        HudScreen(polishState(), null, {}, stateCard = {
                            StateCard(StateCardModel.PickupArrived(facts, clock.millis - 120_000), null)
                        })
                    }
                }
            }
        }
        settle()
    }
    private fun value(tag: String, text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
    @Test fun scale100_labelsUnderFigures() {
        show()
        val figure = value("hud.top.earned", "$98.50").getBoundsInRoot()
        val label = value("hud.top.earned", "Earned").getBoundsInRoot()
        assertTrue(label.top >= figure.bottom)
        compose.onNodeWithTag("state.jobColumn").assertExists()
    }
    @Test fun scale130_labelsBesideFigures() {
        show(1.3f)
        val figure = value("hud.top.earned", "$98.50").getBoundsInRoot()
        val label = value("hud.top.earned", "Earned").getBoundsInRoot()
        assertTrue(label.left >= figure.right)
        assertTrue(label.top < figure.bottom)
        compose.onNodeWithTag("state.jobColumn").assertExists()
    }
    @Test fun scale150_jobColumnDropped() {
        show(1.5f)
        compose.onNodeWithTag("state.jobColumn").assertDoesNotExist()
        compose.onNodeWithTag("state.figure").assertExists()
        compose.onNodeWithText("Store").assertExists()
    }
    @Test fun scale180_heroUsesCompactStep() {
        show(1.8f)
        compose.onNodeWithTag("hud.hero.compact").assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        value("hud.hero.compact", "71").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(30f, layouts.single().layoutInput.style.fontSize.value, .001f)
    }
    @Test
    @Config(sdk = [35], qualifiers = "w390dp-h900dp")
    fun `top row figures fit at font scale 2_0 in the bubble width`() {
        assertTopRowFits("$100.00", "142.6")
    }
    @Test
    @Config(sdk = [35], qualifiers = "w390dp-h900dp")
    fun `top row figures fit at font scale 2_0 with driving glance`() {
        assertTopRowFits("$1000.00", "1042.6", glance = 1.3f)
    }
    private fun assertTopRowFits(earned: String, miles: String, glance: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                GigLogTheme(glance = glance) {
                    CompositionLocalProvider(LocalHudTier provides FontScaleTier.of(2f, LocalGlance.current)) {
                        Box(Modifier.width(390.dp).padding(start = 22.dp, end = 16.dp)) {
                            TopRow(TopRowModel(earned, OnlineSpan.Fixed((12 * 60 + 41) * 60_000L), miles, false))
                        }
                    }
                }
            }
        }
        settle()
        listOf("hud.top.earned" to earned, "hud.top.online" to "12 hr 41 min", "hud.top.miles" to miles)
            .forEach { (tag, figure) ->
                val slot = compose.onNodeWithTag(tag).getBoundsInRoot()
                assertEquals(352f / 3f, slot.width.value, 1f)
                val node = value(tag, figure)
                assertEquals(figure, node.fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
                val bounds = node.getBoundsInRoot()
                assertTrue(figure, bounds.width <= slot.width)
                assertTrue(figure, bounds.left >= slot.left && bounds.right <= slot.right)
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse(figure, layouts.single().hasVisualOverflow)
                assertEquals(figure, layouts.single().layoutInput.text.text)
                assertEquals(1, layouts.single().lineCount)
            }
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `keypad cells grow at the largest tier`() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                GigLogTheme(glance = 1.3f) {
                    CompositionLocalProvider(LocalHudTier provides FontScaleTier.of(2f, LocalGlance.current)) {
                        Keypad({}, {}, {})
                    }
                }
            }
        }
        settle()
        val cell = compose.onNodeWithTag("keypad.8").getBoundsInRoot()
        val digit = value("keypad.8", "8").getBoundsInRoot()
        assertTrue("Cell should grow beyond 38 dp: $cell", cell.height > 38.dp)
        assertTrue(digit.top >= cell.top && digit.bottom <= cell.bottom)
        assertTrue(digit.left >= cell.left && digit.right <= cell.right)
    }
    @Test fun glance112_scalesCardText() {
        show()
        fun fontSize(text: String): Float {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single().layoutInput.style.fontSize.value
        }
        val base = listOf("Store", "at store", "$7.90", "job").associateWith { fontSize(it) }
        compose.runOnIdle { glance = 1.12f }; settle()
        base.forEach { (text, size) -> assertEquals(text, size * 1.12f, fontSize(text), .001f) }
    }
}
