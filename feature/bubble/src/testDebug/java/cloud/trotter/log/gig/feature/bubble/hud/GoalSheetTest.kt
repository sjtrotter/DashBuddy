package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.feature.bubble.hud.goal.*
import cloud.trotter.log.gig.domain.state.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h900dp")
class GoalSheetTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var locale: Locale
    @Before fun setup() { locale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun cleanup() { Locale.setDefault(locale) }
    private var model by mutableStateOf(GoalSheetModel(150, 71.20))
    private val sets = mutableListOf<Int>()
    private val presets = mutableListOf<Int>()
    private var clears = 0
    private fun show() {
        compose.setContent {
            GigLogTheme {
                Box(Modifier.width(352.dp).height(330.dp)) {
                    GoalSheet(model, onSet = { sets += it; model = model.copy(goalDollars = it) },
                        onClear = { clears++ }, onPreset = { presets += it; model = model.copy(goalDollars = it) })
                }
            }
        }
    }
    private fun bar(fill: Float) = compose.onNodeWithTag("goal.bar").assert(
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(fill, 0f..1f)))

    @Test fun `figure steps in whole dollars and never clears below floor`() {
        show()
        compose.onNodeWithText("Daily goal").assertIsDisplayed()
        compose.onNodeWithTag("goal.figure").assertTextEquals("$150")
        compose.onNodeWithTag("goal.minus").performClick()
        compose.onNodeWithTag("goal.figure").assertTextEquals("$145")
        compose.onNodeWithTag("goal.plus").performClick()
        assertEquals(listOf(145, 150), sets)
        compose.runOnIdle { model = model.copy(goalDollars = 5) }
        compose.onNodeWithTag("goal.minus").assertIsNotEnabled().performClick()
        assertEquals(2, sets.size)
        compose.runOnIdle { model = model.copy(goalDollars = 995) }
        compose.onNodeWithTag("goal.plus").performClick()
        compose.onNodeWithTag("goal.figure").assertTextEquals("$999")
        compose.onNodeWithTag("goal.plus").assertIsNotEnabled().performClick()
        assertEquals(3, sets.size)
        compose.onNodeWithTag("goal.minus").performClick()
        compose.onNodeWithTag("goal.figure").assertTextEquals("$994")
        assertEquals(0, clears)
    }

    @Test fun `presets select the current goal and invoke their own callback`() {
        show()
        compose.onNodeWithTag("goal.preset.150").assertIsSelected()
        for (dollars in listOf(100, 125, 150, 175, 200)) {
            compose.onNodeWithTag("goal.preset.$dollars").performClick().assertIsSelected()
            compose.onNodeWithTag("goal.figure").assertTextEquals("$$dollars")
        }
        assertEquals(listOf(100, 125, 150, 175, 200), presets)
        assertTrue(sets.isEmpty())
        compose.onNodeWithTag("goal.preset.150").assertIsNotSelected()
    }

    @Test fun `No goal calls clear and remains visible but disabled without a goal`() {
        show()
        compose.onNodeWithTag("goal.clear").performClick()
        assertEquals(1, clears)
        compose.runOnIdle { model = model.copy(goalDollars = null) }
        compose.onNodeWithText("No goal").assertIsDisplayed()
        compose.onNodeWithTag("goal.clear").assertIsNotEnabled().performClick()
        assertEquals(1, clears)
        compose.onNodeWithTag("goal.figure").assertTextEquals(EMPTY_VALUE)
        compose.onNodeWithTag("goal.minus").assertIsNotEnabled()
        compose.onNodeWithTag("goal.bar").assertDoesNotExist()
        compose.onNodeWithText("Profit today $71.20").assertIsDisplayed()
        compose.onNodeWithText("to go", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("goal.plus").performClick()
        compose.onNodeWithTag("goal.figure").assertTextEquals("$5")
    }

    @Test fun `progress and remaining use today profit with whole dollar remainder`() {
        show()
        bar((71.20 / 150).toFloat())
        assertEquals(6.dp, compose.onNodeWithTag("goal.bar").getBoundsInRoot().height)
        compose.onNodeWithText("Profit today $71.20").assertIsDisplayed()
        compose.onNodeWithText("$79 to go").assertIsDisplayed()
        compose.runOnIdle { model = model.copy(todayProfit = -2.0) }
        bar(0f)
        compose.onNodeWithText("Profit today -$2.00").assertIsDisplayed()
        compose.onNodeWithText("$152 to go").assertIsDisplayed()
        for (profit in listOf(150.0, 171.0)) {
            compose.runOnIdle { model = model.copy(todayProfit = profit) }
            bar(1f)
            compose.onNodeWithText("Met").assertIsDisplayed()
            compose.onNodeWithText("to go", substring = true).assertDoesNotExist()
        }
    }

    @Test fun `unknown profit keeps bar empty and shows em dashes`() {
        model = model.copy(todayProfit = null)
        show()
        bar(0f)
        compose.onNodeWithText("Profit today $EMPTY_VALUE").assertIsDisplayed()
        compose.onNodeWithText("$EMPTY_VALUE to go").assertIsDisplayed()
        compose.runOnIdle { model = model.copy(goalDollars = null) }
        compose.onNodeWithTag("goal.bar").assertDoesNotExist()
        compose.onNodeWithText("Profit today $EMPTY_VALUE").assertIsDisplayed()
    }

    @Test fun `short HUD at large font keeps the bar and both progress lines displayed`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f),
                LocalReducedMotion provides true) {
                GigLogTheme {
                    Box(Modifier.width(400.dp).height(480.dp)) {
                        val state = HudStateMapper.map(
                            region = PlatformRegion(Platform.DoorDash, Mode.Online, Session("live", 1_000)),
                            flow = Flow.Idle, platform = Platform.DoorDash, switchable = emptyList(),
                            earnings = 100.0, miles = 42.5, lastSession = null, net = null,
                            goal = 150.0, todayProfit = 71.2,
                            hasActiveCard = true, hasOfferCard = false, logOpen = false,
                            gas = GasRowModel(3.5f, true), odometerLoaded = true, goalSheetOpen = true)
                        HudScreen(state, null, {}, sheetOpen = true,
                            sheet = { GoalSheet(model, {}, {}, {}) })
                    }
                }
            }
        }
        compose.onNodeWithTag("goal.bar").assertIsDisplayed()
        assertEquals(6.dp, compose.onNodeWithTag("goal.bar").getBoundsInRoot().height)
        compose.onNodeWithText("Profit today $71.20").assertIsDisplayed()
        compose.onNodeWithText("$79 to go").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("goal.progress").getBoundsInRoot().height >= 56.dp)
    }

    @Test fun `busy disables every write and failure appears under presets`() {
        model = model.copy(busy = true, failed = true)
        show()
        for (tag in listOf("goal.clear", "goal.minus", "goal.plus") +
            listOf(100, 125, 150, 175, 200).map { "goal.preset.$it" }) {
            compose.onNodeWithTag(tag).assertIsNotEnabled().performClick()
        }
        assertEquals(0, clears); assertTrue(sets.isEmpty()); assertTrue(presets.isEmpty())
        val failure = compose.onNodeWithText("Not saved. Try again.").assertIsDisplayed().getBoundsInRoot()
        assertTrue(failure.top >= compose.onNodeWithTag("goal.preset.100").getBoundsInRoot().bottom)
        compose.runOnIdle { model = model.copy(failed = false, busy = false) }
        compose.onNodeWithText("Not saved. Try again.").assertDoesNotExist()
        compose.onNodeWithTag("goal.plus").assertIsEnabled()
        compose.onNodeWithText("Save").assertDoesNotExist()
    }
}
