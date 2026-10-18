package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.feature.bubble.MutableClock
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.state.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import org.junit.Before
import org.junit.After

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HudScreenTest {
    @get:Rule val compose = createComposeRule()
    private val clock = MutableClock(T0)
    private lateinit var locale: Locale

    @Before fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        compose.mainClock.autoAdvance = false
    }
    @After fun cleanup() { Locale.setDefault(locale) }

    private fun last() = SessionRecord(
        "last", Platform.DoorDash, T0 - 158 * 60_000, T0 - 38 * 60_000,
        reportedEarnings = 100.0, reportedDurationMillis = null, miles = 42.5,
        deliveries = 5, jobsCompleted = 5, offersReceived = 8, offersAccepted = 5,
        offersDeclined = 2, offersTimeout = 1,
    )

    private fun state(
        flow: Flow = Flow.Idle, resting: Boolean = false, first: Boolean = false, paused: Boolean = false,
        profit: Double? = 71.2, goal: Double? = null, today: Double? = null, logOpen: Boolean = false, cashTipCue: Boolean = false,
    ) = HudStateMapper.map(
        region = if (resting || first) null else PlatformRegion(
            Platform.DoorDash, if (paused) Mode.Paused else Mode.Online, Session("live", T0 - 160 * 60_000 - 30_000)),
        flow = flow, platform = Platform.DoorDash, switchable = emptyList(),
        earnings = 100.0, miles = 42.5, lastSession = if (first) null else last(),
        net = profit?.let { SessionNet("live", it, 0.0, 0.0, 0.0, 0.0) },
        countExpenses = false, goal = goal, todayProfit = today,
        hasActiveCard = !resting && !first, hasOfferCard = flow == Flow.OfferPresented, logOpen = logOpen, gas = GasRowModel(3.5f, true), cashTipCue = cashTipCue,
    )

    private fun show(model: @Composable () -> HudUiState = { state() }, onDock: (DockKind) -> Unit = {}) {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    HudScreen(model(), RibbonModel(T0 - 180 * 60_000,
                        listOf(RibbonSegment(RibbonKind.WAIT, T0 - 180 * 60_000, null)), emptyList(), RibbonKind.WAIT),
                        onDockClick = onDock, stateCard = { Text("State slot") },
                        offer = { Text("Offer slot") }, log = { Text("Log slot") })
                }
            }
        }
    }

    private fun value(tag: String, text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    // A state write from the test (a click callback, a runOnIdle assignment) lands as a pending snapshot
    // notification on the paused main looper; waitForIdle delivers it, and only THEN does a clock
    // advance run the frame that recomposes. Advancing first runs a frame the write has not reached.
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }

    private fun tick(delta: Long) {
        clock.millis += delta
        compose.mainClock.advanceTimeBy(2_000)
    }

    @Test fun `online ticks once a minute under the test clock`() {
        show()
        value("hud.top.online", "40").assertExists()
        tick(20_000)
        value("hud.top.online", "40").assertExists()
        tick(15_000)
        value("hud.top.online", "41").assertExists()
        value("hud.top.online", "40").assertDoesNotExist()
    }

    @Test fun `top row keeps its slots across live states`() {
        var flow by mutableStateOf(Flow.Idle)
        show({ state(flow = flow) })
        val tags = listOf("hud.top.earned", "hud.top.online", "hud.top.miles")
        val bounds = tags.map { compose.onNodeWithTag(it).getBoundsInRoot() }
        for (next in listOf(Flow.TaskPickupArrived, Flow.TaskDropoffNavigation)) {
            compose.runOnIdle { flow = next }
            settle()
            assertEquals(bounds, tags.map { compose.onNodeWithTag(it).getBoundsInRoot() })
            value("hud.top.earned", "$100.00").assertExists()
            value("hud.top.online", "40").assertExists()
            value("hud.top.miles", "42.5").assertExists()
        }
    }

    @Test fun `goal pill shows set goal with no goal`() {
        show()
        compose.onNodeWithText("Set goal").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("hud.goal").assertHasNoClickAction()
        compose.onNodeWithText("to go").assertDoesNotExist()
    }

    @Test fun `goal pill shows the remainder in progress`() {
        show({ state(goal = 150.0, today = 71.2) })
        compose.onNodeWithText("$79").assertIsDisplayed()
        compose.onNodeWithText("to go").assertIsDisplayed()
    }

    @Test fun `goal pill shows no text when met`() {
        show({ state(goal = 150.0, today = 170.0) })
        compose.onNodeWithText("to go").assertDoesNotExist()
        compose.onNodeWithText("over goal", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Goal met").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test fun `hero shows dollars large and cents small`() {
        show()
        value("hud.hero", "71").assertIsDisplayed()
        value("hud.hero", ".20").assertIsDisplayed()
        // The nodes expose the actual text layouts, so this fails if both use one text size.
        val dollars = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val cents = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        value("hud.hero", "71").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(dollars) }
        value("hud.hero", ".20").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(cents) }
        org.junit.Assert.assertTrue(dollars.single().layoutInput.style.fontSize > cents.single().layoutInput.style.fontSize)
    }

    @Test fun `null profit shows the em dash hero`() {
        show({ state(profit = null) })
        value("hud.hero", EMPTY_VALUE).assertIsDisplayed()
        compose.onNodeWithText("$0.00").assertDoesNotExist()
    }

    @Test fun `offer mode shows the offer slot and hides the chrome below the head`() {
        show({ state(flow = Flow.OfferPresented) })
        compose.onNodeWithTag("hud.slot.offer").assertIsDisplayed()
        compose.onNodeWithText("Offer slot").assertIsDisplayed()
        listOf("hud.top.earned", "hud.top.online", "hud.top.miles", "hud.hero", "hud.goal", "hud.ribbon")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        DockKind.entries.forEach { compose.onNodeWithTag("hud.dock.$it").assertDoesNotExist() }
    }

    @Test fun `log block calls back and log mode shows the log slot with log lit`() {
        var open by mutableStateOf(false)
        var calls = 0
        show({ state(logOpen = open) }, { if (it == DockKind.LOG) { calls++; open = !open } })
        compose.onNodeWithTag("hud.dock.LOG").performClick()
        settle()
        assertEquals(1, calls)
        compose.onNodeWithTag("hud.slot.log").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.LOG").assertIsSelected()
        compose.onNodeWithTag("hud.dock.LOG").performClick()
        settle()
        compose.onNodeWithTag("hud.slot.state").assertIsDisplayed()
    }

    @Test fun `resting log opens and closes while keeping settings reachable`() {
        var open by mutableStateOf(false)
        show({ state(resting = true, logOpen = open) }, { if (it == DockKind.LOG) open = !open })
        compose.onNodeWithTag("hud.dock.LOG").performClick()
        settle()
        compose.onNodeWithTag("hud.slot.log").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.LOG").assertIsSelected()
        compose.onNodeWithTag("hud.dock.SETTINGS").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.LOG").performClick()
        settle()
        compose.onNodeWithText("Last dash").assertIsDisplayed()
    }

    @Test fun `resting shows the last dash and four dock blocks`() {
        show({ state(resting = true) })
        compose.onNodeWithText("Last dash").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        value("hud.top.earned", "$100.00").assertExists()
        value("hud.top.online", "2").assertExists()
        value("hud.top.miles", "42.5").assertExists()
        DockKind.entries.forEach { compose.onNodeWithTag("hud.dock.$it").assertIsDisplayed() }
        compose.onNodeWithTag("hud.ribbon").assertDoesNotExist()
        org.junit.Assert.assertTrue(state(resting = true).top.dimmed)
    }

    @Test fun `resting ended time ticks under the test clock`() {
        show({ state(resting = true) })
        compose.onNodeWithText("38").assertExists()
        tick(60_000)
        compose.onNodeWithText("39").assertExists()
        compose.onNodeWithText("38").assertDoesNotExist()
    }

    @Test fun `first run shows em dashes and a disabled cash tip`() {
        show({ state(first = true, profit = null) })
        listOf("hud.top.earned", "hud.top.online", "hud.top.miles").forEach { value(it, EMPTY_VALUE).assertExists() }
        compose.onNodeWithText("No dash yet").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.CASH_TIP").assertIsNotEnabled()
        compose.onNodeWithTag("hud.dock.EXPENSE").assertIsNotEnabled()
    }

    @Test fun `ribbon is present live and absent at rest`() {
        var resting by mutableStateOf(false)
        show({ state(resting = resting) })
        compose.onNodeWithTag("hud.ribbon").assertIsDisplayed()
        compose.runOnIdle { resting = true }
        settle()
        compose.onNodeWithTag("hud.ribbon").assertDoesNotExist()
    }

    @Test fun `paused dash says so in the head`() {
        show({ state(flow = Flow.Idle, paused = true) })
        compose.onNodeWithText("paused").assertIsDisplayed()
        compose.onNodeWithText("DoorDash").assertIsDisplayed()
        compose.onNodeWithTag("hud.ribbon").assertIsDisplayed()
    }

    @Test fun `door state accents the cash tip block`() {
        val door = StateCardModel.DropoffArrived(JobFacts("Store", null, null, null), T0)
        var clicked: DockKind? = null
        show({ state(flow = Flow.TaskDropoffArrived, cashTipCue = door.cuesCashTip) }, { clicked = it })
        compose.onNodeWithTag("hud.dock.CASH_TIP").assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Suggested"))
            .performClick()
        settle()
        assertEquals(DockKind.CASH_TIP, clicked)
    }

    private companion object { const val T0 = 1_700_000_000_000L }
}
